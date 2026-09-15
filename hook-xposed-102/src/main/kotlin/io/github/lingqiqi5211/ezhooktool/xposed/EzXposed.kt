package io.github.lingqiqi5211.ezhooktool.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterfaceWrapper
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.common.ModuleResources
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ApplicationLifecycle
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookDiagnostics
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.function.Consumer

/**
 * libxposed API 102 的运行时入口。
 *
 * 推荐初始化顺序：
 *
 * 1. 在 `XposedModule.onModuleLoaded` 里调用 [initOnModuleLoaded]
 * 2. 在 `XposedModule.onPackageLoaded` 里调用 [initOnPackageLoaded]
 * 3. 在 `XposedModule.onPackageReady` 里调用 [initOnPackageReady]；需要在
 *    `AppComponentFactory` 创建前安装 hook 时，改用 [initOnPackageLoadedAsTargetReady]
 *    或 `onSystemServerStarting` 里调用 [initOnSystemServerStarting]
 * 4. 通过 [onTargetReady] 注册「目标进程准备好后跑什么」
 *
 * API 102 热重载不会自动重放 `onModuleLoaded` 或 package 生命周期回调。模块应在
 * `onHotReloaded` 中重新执行自己的运行时初始化（包括 [initOnModuleLoaded] 与 [onTargetReady] 注册），
 * 再调用 [handleHotReloaded] 或 [restoreHotReloadedAutomatically] 恢复目标 snapshot。
 *
 * 行为约定：
 *
 * - [base] 在 [initOnModuleLoaded] 后可用
 * - [classLoader] 在 [initOnPackageReady]、[initOnPackageLoadedAsTargetReady]
 *   或 [initOnSystemServerStarting] 后代表当前进程反射环境
 * - [appContext] 采用懒解析，期望指向目标进程 application；如果应用尚未创建，请改用 [appContextOrNull] 或稍后访问
 * - [modulePath] / [moduleRes] 在 [initOnModuleLoaded] 后可用
 *
 * 默认热重载会把同步初始化中的 DSL/Java helper hook 按 executable、priority、exceptionMode 自动
 * 聚合，并为物理 hook 分配稳定内部 ID；在 [handleHotReloaded] 成功重建后才清理遗留旧 handle，
 * 通常无需逐条写 `reloadKey(...)`。同一组内的逻辑 hook 可增删重排，也允许新增或删除目标、
 * priority/exceptionMode 组及显式 hook ID。相同 executable + ID 的旧 hook 由 framework 单条原子替换，
 * 新 hook 先安装，未继续声明的旧 hook 最后才移除。
 * 需要跨大规模重排保持精确 identity 时使用 [HotReloadSession] 的显式 `reloadKey(...)`；
 * [HookReloadBatch] 和带 [handleHotReloaded] 的 `onOldHooks` 参数保留给自定义流程。
 *
 * 热重载是可选特性。hook ID、`replaceHook` 和热重载回调都是 libxposed API 102 才有的，运行在只实现
 * API 101 的 framework 上时（[XposedFeature.HOT_RELOAD] 不可用），本库会自动退化：不再分配 hook ID、
 * 不再调用 `setId`，hook 安装与其它所有能力照常工作；无法降级的入口都标注了 [RequiresXposedApi]，
 * 会明确报错。在 102 framework 上想主动关掉这套机制，把 [hotReloadEnabled] 设为 `false`。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak")
object EzXposed {
    private var appContextValue: Context? = null

    @JvmStatic
    /** libxposed 基础接口实例。 */
    lateinit var base: XposedInterface
        internal set

    /** [base] 的安全读取入口；尚未 [initOnModuleLoaded] 时为 `null`。 */
    internal val baseOrNull: XposedInterface?
        get() = if (::base.isInitialized) base else null

    private var moduleEntry: XposedInterfaceWrapper? = null

    /** 目标进程 snapshot；进入目标就绪初始化方法后填充。 */
    private var targetSnapshot: TargetSnapshot? = null

    private val targetReadyDispatcher = TargetReadyDispatcher()
    private var moduleResourcesInitialized = false

    /** 当前 generation 首次就绪初始化的状态，不表示后续即时回调或整次热重载的结果。 */
    @JvmStatic
    val targetReadyState: TargetReadyState get() = targetReadyDispatcher.state

    /** 首次就绪初始化的失败原因；失败不会自动重试，新 generation 会清空。 */
    @JvmStatic
    val targetReadyFailure: Throwable? get() = targetReadyDispatcher.failure

    /** 当前 module generation 的事务外 helper hook 兜底 ID 分配器；热重载未生效时为 `null`。 */
    private var automaticHookIds: AutomaticHookIdAllocator? = null

    /** 默认 [onTargetReady] 初始化使用的聚合事务；不需要模块为每条 hook 写 reloadKey。 */
    private var automaticHookBatch: HookReloadBatch? = null

    /**
     * 同一 HotReloadedParam 可能被内部初始化入口重复传递，只在首次看到时重置 generation 状态。
     *
     * 声明成 `Any` 而不是 `HotReloadedParam`：这个字段在 101 framework 上也会被读写，不能引用
     * 只有 102 才有的类型。
     */
    private var currentHotReloadParam: WeakReference<Any>? = null

    /**
     * 仅在 [HotReloadSession] 重建 hook 的同步窗口内设置。
     *
     * ThreadLocal 避免把 session（以及新 module classloader）泄漏给异步任务；异步注册 hook
     * 本来就不可能构成一次可原子收尾的热重载，因此必须在 session 的同步回调里完成。
     */
    private val activeHotReloadSession = ThreadLocal<HotReloadSession?>()
    private val activeHotReloadAttempt = ThreadLocal<HotReloadAttempt?>()

    @Volatile
    private var hotReloadFailure: Throwable? = null

    internal fun markHotReloadIrreversible() {
        activeHotReloadAttempt.get()?.markIrreversible()
    }

    internal fun recordHotReloadFailure(cause: Throwable) {
        hotReloadFailure = cause
    }

    private fun requireHealthyHotReload() {
        hotReloadFailure?.let {
            throw IllegalStateException("A previous hot reload failed; restart the target process before continuing.", it)
        }
    }

    /**
     * 是否启用安全模式。
     *
     * 开启后，hook 回调异常会被捕获、记日志并回退到原始调用。
     */
    @JvmStatic
    @Volatile
    var safeMode: Boolean = true

    @JvmStatic
    /**
     * 模块是否启用热重载。默认 `true`。
     *
     * 关掉之后，本库不再为 hook 分配自动 ID、不再调用 `HookBuilder.setId`，也不再把
     * [onTargetReady] 的同步初始化包进默认聚合事务；[handleHotReloading] 会直接返回 `false`
     * 让 framework 放弃热重载。适合明确不需要热重载、希望 hook 安装路径尽量薄的模块。
     *
     * 请在 [initOnModuleLoaded] 之前设置：默认聚合事务是在那里创建的。
     *
     * 这个开关只表达「模块要不要」；framework 支不支持见 [XposedFeature.HOT_RELOAD]，
     * 两者都满足时 [hotReloadActive] 为 `true`。
     */
    @Volatile
    var hotReloadEnabled: Boolean = true

    /** 当前 framework 侧的 libxposed API 版本；[base] 尚未初始化时为 0。 */
    @JvmStatic
    val frameworkApiVersion: Int
        get() = XposedApiCompat.apiVersion

    /** framework 提供热重载（[XposedFeature.HOT_RELOAD]）且模块启用（[hotReloadEnabled]）时才为 `true`。 */
    @JvmStatic
    val hotReloadActive: Boolean
        get() = hotReloadEnabled && XposedFeature.HOT_RELOAD.isSupported

    /** 当前包名。 */
    @JvmStatic
    var packageName: String = ""
        private set

    /** 当前进程名。 */
    @JvmStatic
    var processName: String = ""
        private set

    /** 当前是否运行在 `system_server`。 */
    @JvmStatic
    var isSystemServer: Boolean = false
        private set

    @JvmStatic
    /**
     * 当前模块 apk 路径；调用 [initOnModuleLoaded] 后可用。
     *
     * 直接访问该字段不会经过任何兜底；在 [initOnModuleLoaded] 之前读会抛 Kotlin lateinit 错误。
     * 库内部读取请走 `requireModulePath()`，包含 `IllegalStateException` 友好提示。
     */
    lateinit var modulePath: String
        private set

    /** [modulePath] 的安全读取入口；尚未 [initOnModuleLoaded] 时为 `null`。 */
    internal val modulePathOrNull: String?
        get() = if (::modulePath.isInitialized) modulePath else null

    @JvmStatic
    /** 当前模块资源；调用 [initOnModuleLoaded] 后可用。 */
    lateinit var moduleRes: Resources
        private set

    /** [moduleRes] 的安全读取入口；尚未 [initOnModuleLoaded] 时为 `null`。 */
    internal val moduleResOrNull: Resources?
        get() = if (::moduleRes.isInitialized) moduleRes else null

    /** 当前默认 `ClassLoader`。 */
    @JvmStatic
    val classLoader: ClassLoader
        get() = EzReflect.classLoader

    /** 始终可用的 `ClassLoader`；未初始化时回退到 `SystemClassLoader`。 */
    @JvmStatic
    val safeClassLoader: ClassLoader
        get() = EzReflect.safeClassLoader

    /** 当前进程的 application context；过早访问时会抛异常。 */
    @JvmStatic
    val appContext: Context
        @Synchronized get() {
            appContextValue?.let { return it }

            val current =
                getCurrentApplicationContext()
                    ?: throw NullPointerException(
                        "Cannot get appContext now, is Application onCreate finished?",
                    )
            appContextValue = current
            return current
        }

    /** 当前进程的 application context；尚未可用时返回 `null`。 */
    @JvmStatic
    val appContextOrNull: Context?
        @Synchronized get() {
            appContextValue?.let { return it }
            val current = getCurrentApplicationContext() ?: return null
            appContextValue = current
            return current
        }

    @JvmStatic
    /**
     * 手动缓存当前进程的 application context。
     *
     * 默认行为：仅当 [appContext] 尚未初始化时才写入；已初始化时入参 `context` 会被忽略，
     * 避免不同 hook 回调以非 application context（如 Activity / ContextWrapper）反复覆盖全局缓存。
     *
     * 默认只缓存 `context.applicationContext`（或传入的 Application）；不可用时抛出 IllegalStateException。
     * [force] 为 `true` 时原样缓存指定 Context 并覆盖旧值；调用者自行承担其生命周期，避免传入 Activity。
     *
     * [injectModuleAssetPath] 的资源注入副作用始终按入参 `context` 执行，与 [force] 无关。
     *
     * 推荐通过 [runOnApplicationAttach] 让库自动在 `Application.attach` 阶段填充 application context，
     * 而不是在业务 hook 回调里手工调用本方法。
     */
    @JvmOverloads
    fun initAppContext(
        context: Context? = getCurrentApplicationContext(),
        injectModuleAssetPath: Boolean = false,
        force: Boolean = false,
    ) {
        val resolved =
            context ?: throw NullPointerException(
                "Cannot init appContext with null context.",
            )
        synchronized(this) {
            if (force || appContextValue == null) {
                appContextValue =
                    if (force) {
                        resolved
                    } else {
                        resolved.applicationContext
                            ?: (resolved as? android.app.Application)
                            ?: throw IllegalStateException(
                                "Application context is not available for ${resolved.javaClass.name}; " +
                                    "wait for Application.attach or use force=true to explicitly retain this Context.",
                            )
                    }
            }
        }
        if (injectModuleAssetPath) {
            addModuleAssetPath(resolved)
        }
    }

    /**
     * 注册「`Application.attach(Context)` 之后跑什么」的回调。
     *
     * 第一次注册时库会自动 hook `Application.attach`，回调按注册顺序在 attach after 阶段执行。
     *
     * - 回调里收到的 `context` 是目标进程的 application，库会在触发回调前把它写入 [appContext] 缓存（仅当未初始化时）。
     * - 如果注册时 application 已经 attach 过（即 [appContextOrNull] 非 null），新注册的回调会立即在当前线程同步触发一次。
     * - callback 抛出的异常会被库捕获并记日志，不会影响其它已注册回调，也不会影响目标 app 的 `Application.attach`。
     *
     * 调用前必须先完成 [initOnModuleLoaded]（hook API 依赖 [base]）。
     */
    @JvmStatic
    fun runOnApplicationAttach(callback: ApplicationAttachCallback) {
        check(::base.isInitialized) {
            "runOnApplicationAttach requires EzXposed.initOnModuleLoaded to be called first."
        }
        ApplicationLifecycle.register(callback)
    }

    /**
     * 仅供 [ApplicationLifecycle] 内部回填：在 `Application.attach` 触发瞬间把 application context 写进缓存。
     *
     * 仅在缓存为空时写入，不会覆盖调用方已经显式塞入的值。
     */
    internal fun cacheApplicationContextFromLifecycle(context: Context) {
        synchronized(this) {
            if (appContextValue == null) {
                appContextValue = context
            }
        }
    }

    /**
     * 显式重新创建模块资源；创建失败时保留上一次可用资源。
     *
     * 使用 [initOnModuleLoaded] 记录的模块 apk 路径，创建可独立访问的 [moduleRes]。
     * 通常不需要手动调用；[initOnModuleLoaded] 会在每个 generation 自动初始化一次。
     */
    @JvmStatic
    fun initModuleResources() {
        moduleRes = ModuleResources.create(requireModulePath())
        moduleResourcesInitialized = true
    }

    /**
     * 添加模块路径到目标 `Context.resources`。允许通过“R.xx.xxx”直接使用模块资源。
     *
     * 如果您想使用它，请执行以下操作：
     *
     * 1. 修改资源 ID，使其不与挂钩的应用程序或其他 Xposed 模块冲突。
     *
     * Kotlin Gradle DSL：
     *
     * `androidResources.additionalParameters("--allow-reserved-package-id", "--package-id", "0x64")`
     *
     * Groovy：
     *
     * `aaptOptions.additionalParameters '--allow-reserved-package-id', '--package-id', '0x64'`
     *
     * `0x64` 是资源 ID。您可以将其更改为您想要的任何值。推荐范围是“[0x30，0x6F]”。
     *
     * 2. 确保[EzXposed]已初始化。
     *
     * 3. 使用前调用该函数。
     */
    @JvmStatic
    fun addModuleAssetPath(context: Context) {
        addModuleAssetPath(context.resources)
    }

    /**
     * 将模块资源路径注入到指定 [resources]。
     *
     * 调用前需要先完成 [initOnModuleLoaded]。
     */
    @JvmStatic
    fun addModuleAssetPath(resources: Resources) {
        // 交给 EzResources：R 以上走 ResourcesLoader，更低回退 addAssetPath，注入过的会登记。
        check(EzResources.inject(resources)) {
            "Failed to add the module asset path to $resources"
        }
    }

    /**
     * 在 `onModuleLoaded` 阶段初始化运行时基础信息。
     *
     * 这里会保存 libxposed 基础接口和进程元信息；同一 generation 的模块资源成功创建后不再重建，
     * 创建失败可再次调用。显式刷新请用 [initModuleResources]。不会初始化目标进程 [classLoader]。
     */
    @JvmStatic
    fun initOnModuleLoaded(
        base: XposedInterface,
        param: XposedModuleInterface.ModuleLoadedParam,
    ) {
        // 先把可选特性解析成掩码。后面所有 isSupported 都只是一次位测试，包括紧接着那句 isHotReloadedParam。
        XposedApiCompat.resolve(base)
        // API 102 不会在热重载时自动重放 onModuleLoaded；模块应从 onHotReloaded 调用本方法。
        // HotReloadedParam 也标识一个明确的新 generation；即使 framework wrapper 被复用，也不能
        // 让旧 callback 因旧 snapshot 提前执行。101 framework 上这个判断恒为 false。
        val hotReloadParam = param.takeIf { XposedApiCompat.isHotReloadedParam(it) }
        val isNewGeneration =
            !::base.isInitialized ||
                this.base !== base ||
                (hotReloadParam != null && hotReloadParam !== currentHotReloadParam?.get())
        if (isNewGeneration) {
            targetReadyDispatcher.reset()
            moduleResourcesInitialized = false
            targetSnapshot = null
            appContextValue = null
            packageName = ""
        }
        this.base = base
        this.moduleEntry = base as? XposedInterfaceWrapper
        modulePath = base.moduleApplicationInfo.sourceDir
        if (isNewGeneration) {
            // 不支持或未启用热重载时不建立 ID 分配器与聚合事务：hook 会走不带 ID 的直装路径。
            val active = hotReloadActive
            automaticHookIds =
                if (active) {
                    AutomaticHookIdAllocator(base.moduleApplicationInfo.packageName)
                } else {
                    null
                }
            automaticHookBatch = if (active) createAutomaticHookBatch(base, param) else null
        }
        currentHotReloadParam = hotReloadParam?.let(::WeakReference)
        if (!moduleResourcesInitialized) initModuleResources()
        processName = param.processName
        isSystemServer = param.isSystemServer
    }

    /** 在 `onPackageLoaded` 阶段记录当前包名。 */
    @JvmStatic
    fun initOnPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        packageName = param.packageName
    }

    @JvmStatic
    /**
     * 在 `onPackageLoaded` 阶段把目标标记为就绪，并立即触发 [onTargetReady]。
     *
     * 适合必须早于 `AppComponentFactory` 创建安装的 hook。此阶段只能使用
     * [XposedModuleInterface.PackageLoadedParam.getDefaultClassLoader]；为保持初次加载与热重载
     * 使用同一个 ClassLoader，随后调用 [initOnPackageReady] 会被忽略。
     *
     * `getDefaultClassLoader` 是 Android Q 才有的 API，所以这条路径也要求 Q；更低版本请改用
     * [initOnPackageReady]。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun initOnPackageLoadedAsTargetReady(
        param: XposedModuleInterface.PackageLoadedParam,
    ) {
        initializePackageTargetAndDispatch(
            param.packageName,
            param.defaultClassLoader,
            param.applicationInfo,
        )
    }

    /**
     * 在 `onPackageReady` 阶段初始化可直接用于反射的 [classLoader]。
     *
     * 从这个阶段开始，`findClass` / `findMethod` 一类 API 才应默认面向目标应用类使用。
     * 同时会建立目标进程 snapshot，触发已通过 [onTargetReady] 注册的回调。
     * 如果已通过 [initOnPackageLoadedAsTargetReady] 选择早期时机，本次调用不会改变其状态。
     */
    @JvmStatic
    fun initOnPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        initializePackageTargetAndDispatch(
            param.packageName,
            param.classLoader,
            param.applicationInfo,
        )
    }

    /**
     * 在 `onSystemServerStarting` 阶段初始化 `system_server` 的反射环境。
     *
     * 这个阶段通常没有常规意义上的应用上下文，因此不要默认依赖 [appContext]。
     * 同时会建立 system_server snapshot，触发已通过 [onTargetReady] 注册的回调。
     */
    @JvmStatic
    fun initOnSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!targetReadyDispatcher.start {
                EzReflect.init(param.classLoader)
                targetSnapshot =
                    TargetSnapshot(
                        packageName = packageName,
                        processName = processName,
                        classLoader = param.classLoader,
                        applicationInfo = null,
                        isSystemServer = true,
                    )
            }
        ) {
            return
        }
        runTargetReadyCallbacks()
    }

    /**
     * 注册「目标进程准备好后跑什么」的回调。
     *
     * 初次加载：在 [initOnPackageLoadedAsTargetReady]、[initOnPackageReady]
     * 或 [initOnSystemServerStarting] 末尾触发。
     * 热重载：[handleHotReloaded] 或 [HotReloadSession.restore] 还原 snapshot 后触发。
     *
     * 允许多次注册；正在分发时的新回调进入队尾，执行后释放引用。首次初始化成功后再注册，会立即
     * 在当前线程执行且不保留。首次初始化失败后不自动重试，再注册会抛 IllegalStateException。
     * 可通过 [targetReadyState] 与 [targetReadyFailure] 查询首次初始化结果。
     *
     * 默认聚合事务覆盖首次分发中同步执行的回调，包括重入注册。分发成功后的即时回调不属于该批次；
     * 需要可靠跨代替换时使用显式 `reloadKey(...)` 或自定义旧 handle 处置。
     *
     * @param callback 回调，可以从 [EzXposed.packageName] / [classLoader] / [isSystemServer] 读取当前上下文
     */
    @JvmStatic
    fun onTargetReady(callback: TargetReadyCallback) {
        if (targetReadyDispatcher.register(callback)) runCallbackSafely(callback)
    }

    /**
     * 在覆写的 `onHotReloading` 里直接调用。
     *
     * 把当前 [TargetSnapshot] 拍平成 `Array<Any?>` 透传给 `HotReloadingParam.setSavedInstanceState`，
     * 并返回 `true` 允许重载继续。如果当前还没通过 [initOnPackageLoadedAsTargetReady]、[initOnPackageReady] 或
     * [initOnSystemServerStarting] 建立目标 snapshot，会返回 `false`——此时没有可恢复的状态，
     * 热重载没有意义。
     *
     * snapshot 数组的内容（`String`、`ClassLoader`、`ApplicationInfo`、`Boolean`、`Integer`）
     * 全部由 boot / system / app classloader 加载，符合 libxposed 102「setSavedInstanceState 不接受
     * 旧 module classloader 加载的对象」的硬约束。
     *
     * [extra] 是使用者要跨代透传的额外数据（如 hook 里记录的宿主对象、构造器 `this`、额外
     * classloader）。Map、Collection 和数组会复制为有界无环快照；其它宿主对象保留引用。
     * 模块对象及依赖模块的子加载器会被拒绝，framework 仍会执行最终校验。
     *
     * **返回值的含义由调用方 `onHotReloading` 的返回值传递给 framework**：
     * 返回 `true` 表示同意热重载；返回 `false` 表示模块自身要求 framework 放弃本次热重载请求。
     * 也就是说当本方法返回 `false` 时，请直接把它作为 `onHotReloading` 的返回值（默认写法即为 `return EzXposed.handleHotReloading(param)`）。
     *
     * 想完全自定义 saved state 的进阶用法直接覆写 `onHotReloading`，不要调用本方法。
     *
     * @param extra 使用者透传的跨代数据，默认空
     */
    @JvmStatic
    @JvmOverloads
    @RequiresXposedApi(102)
    fun handleHotReloading(
        param: XposedModuleInterface.HotReloadingParam,
        extra: Array<Any?> = emptyArray(),
    ): Boolean {
        if (!XposedFeature.HOT_RELOAD.isSupported) {
            EzReflect.logger.warn(
                "EzXposed",
                "Hot reload rejected: the current framework reports libxposed API " +
                    "$frameworkApiVersion; API ${XposedFeature.HOT_RELOAD.minApiVersion} is required.",
            )
            return false
        }
        // hotReloadEnabled 是模块自己的显式声明，静默拒绝即可。
        if (!hotReloadEnabled) return false
        if (hotReloadFailure != null || targetReadyState != TargetReadyState.SUCCEEDED) {
            EzReflect.logger.warn("EzXposed", "Hot reload rejected: initialization or reload did not succeed; restart the target process.")
            return false
        }
        val snapshot = targetSnapshot ?: return false
        automaticHookBatch?.hotReloadBlockReason?.let { reason ->
            EzReflect.logger.warn("EzXposed", "Hot reload rejected: $reason")
            return false
        }
        // 资源注入的跨代状态（宿主 Resources、旧 loader）都是框架对象，可以合法进 saved state。
        val savedExtra = CrossGenerationState.snapshotArray(extra)
        XposedApiCompat.Api102.setSavedInstanceState(param, snapshot.toCrossGenArray(savedExtra, EzResources.captureForHotReload()))
        return true
    }

    /**
     * 在覆写的 `onHotReloaded` 里直接调用。
     *
     * 这里完成以下事情：
     *
     * 未传 `onOldHooks` 时，会启用默认自动流程：
     *
     * 1. 在新 hook 注册前读取旧 handle 的 executable 与 hook ID；
     * 2. 还原 snapshot，触发 [onTargetReady] 同步重新安装 hook；
     * 3. 使用默认聚合 ID 或显式 ID 原子替换同名旧 hook；
     * 4. 仅在新 generation 完整安装成功后 unhook 未继续声明的旧 handle。
     *
     * 因此默认流程不会出现“先全部 unhook、再等待新 hook 安装”的空窗。传入 `onOldHooks` 即改为
     * 完全自定义旧 handle 处置，工具不会替调用方 unhook。
     *
     * 默认自动流程要求 hook 初始化位于 [onTargetReady] 的同步回调中，且旧 handle 都有 hook ID。
     * 初次从无 ID 的旧版本迁移时会拒绝本次热重载，保留旧实现并要求完整重启一次目标进程。
     *
     * @param onOldHooks 上一代 hook handle 的自定义处置策略；`null` 表示使用默认自动原子替换流程
     * @param onExtra    接收 [handleHotReloading] 透传的 `extra`；`null` 表示忽略
     */
    @JvmStatic
    @JvmOverloads
    @RequiresXposedApi(102)
    fun handleHotReloaded(
        base: XposedInterface,
        param: XposedModuleInterface.HotReloadedParam,
        onOldHooks: Consumer<List<XposedInterface.HookHandle>>? = null,
        onExtra: Consumer<Array<Any?>>? = null,
    ) {
        XposedApiCompat.requireFeature(XposedFeature.HOT_RELOAD, "EzXposed.handleHotReloaded")
        if (onOldHooks == null) {
            restoreHotReloadedAutomatically(base, param, onExtra)
        } else {
            val restored =
                restoreHotReloaded(
                    base = base,
                    param = param,
                    onOldHooks = onOldHooks,
                    onExtra = onExtra,
                )
            check(restored) {
                "Custom hot reload requires saved state created by EzXposed.handleHotReloading."
            }
        }
    }

    /**
     * 默认自动热重载的精简入口。
     *
     * API 102 不会重放 `onModuleLoaded`，因此这个重载会先初始化新 generation，并注册
     * [targetReady]，再恢复 snapshot、同步安装 hook 和收尾旧 handle。普通模块可以在
     * `onHotReloaded` 中直接调用它，而无需重复写初始化样板。
     *
     * [targetReady] 中的 hook 必须同步注册；未声明 ID 的 DSL / Java helper hook 会自动聚合为
     * 带稳定内部 ID 的物理 hook。
     * 需要自定义旧 handle 处置时改用带 `onOldHooks` 参数的 [handleHotReloaded]。
     *
     * @return 新 hook 安装、原子替换与遗留旧 hook 清理的统计结果
     */
    @JvmStatic
    @JvmOverloads
    @RequiresXposedApi(102)
    fun handleHotReloadedWithTargetReady(
        base: XposedInterface,
        param: XposedModuleInterface.HotReloadedParam,
        targetReady: TargetReadyCallback,
        onExtra: Consumer<Array<Any?>>? = null,
    ): AutomaticHotReloadResult {
        requireHealthyHotReload()
        initOnModuleLoaded(base, param)
        onTargetReady(targetReady)
        return restoreHotReloadedAutomatically(base, param, onExtra)
    }

    /**
     * 执行默认自动热重载流程，并返回实际替换与清理数量。
     *
     * 与 [handleHotReloaded] 的无 `onOldHooks` 形式等价；需要把结果写入日志、通知或 UI 时使用本入口。
     */
    @JvmStatic
    @JvmOverloads
    @RequiresXposedApi(102)
    fun restoreHotReloadedAutomatically(
        base: XposedInterface,
        param: XposedModuleInterface.HotReloadedParam,
        onExtra: Consumer<Array<Any?>>? = null,
    ): AutomaticHotReloadResult {
        XposedApiCompat.requireFeature(XposedFeature.HOT_RELOAD, "EzXposed.restoreHotReloadedAutomatically")
        check(hotReloadEnabled) {
            "Automatic hot reload requires EzXposed.hotReloadEnabled to stay true."
        }
        requireHealthyHotReload()
        // 先初始化新 generation，确保默认 batch 在注册新 hook 前已持有旧 handle snapshot。
        initOnModuleLoaded(base, param)
        check(targetReadyState == TargetReadyState.NOT_STARTED) {
            "Automatic hot reload requires target-ready initialization that has not started."
        }
        // 正常 framework 会为每一代创建新 entry；这里仍显式创建一次事务，兼容复用 wrapper 的实现，
        // 并确保本次 reload 不会意外复用已提交的初始加载 batch。
        automaticHookIds = AutomaticHookIdAllocator(base.moduleApplicationInfo.packageName)
        automaticHookBatch = createAutomaticHookBatch(base, param)
        val batch =
            automaticHookBatch ?: throw IllegalStateException(
                "Automatic hot reload batch is unavailable. Call EzXposed.initOnModuleLoaded first.",
            )
        batch.captureOldHooks(XposedApiCompat.Api102.oldHookHandles(param))
        check(targetReadyDispatcher.hasCallbacks) {
            "Automatic hot reload requires at least one EzXposed.onTargetReady callback in the new generation."
        }
        val restored =
            restoreHotReloaded(
                base = base,
                param = param,
                // 默认流程禁止旧入口的提前 unhook；收尾统一放到新 hook 成功安装之后。
                onOldHooks = null,
                onExtra = onExtra,
            )
        check(restored) {
            "Automatic hot reload requires saved state created by EzXposed.handleHotReloading."
        }
        val result =
            try {
                batch.finishHotReload()
            } catch (t: Throwable) {
                recordHotReloadFailure(t)
                throw t
            }
        return AutomaticHotReloadResult(
            installedHookCount = result.logicalHookCount + result.explicitHookCount,
            atomicallyReplacedHookCount = result.atomicallyReplacedHookCount,
            removedOldHookCount = result.removedOldHookCount,
        )
    }

    /**
     * [HotReloadSession] 与自定义热重载使用的底层恢复入口。
     *
     * 自定义 `onOldHooks` 的调用方自行决定旧 handle 的收尾；本入口绝不再默认全量 unhook。
     * [HotReloadSession] 和默认自动流程会要求把 `onTargetReady` 的失败原样抛出，以便 framework
     * 明确判定本次重载没有成功。
     *
     * @return 是否恢复到了由 [handleHotReloading] 保存的 EzHookTool snapshot
     */
    internal fun restoreHotReloaded(
        base: XposedInterface,
        param: XposedModuleInterface.HotReloadedParam,
        onOldHooks: Consumer<List<XposedInterface.HookHandle>>?,
        onExtra: Consumer<Array<Any?>>?,
    ): Boolean {
        requireHealthyHotReload()
        check(activeHotReloadAttempt.get() == null) { "Hot reload cannot be nested." }
        val saved = XposedApiCompat.Api102.savedInstanceState(param)
        val snapshot = TargetSnapshot.tryRestore(saved) ?: return false
        initOnModuleLoaded(base, param)
        check(targetReadyState == TargetReadyState.NOT_STARTED) {
            "Hot reload requires target-ready initialization that has not started."
        }
        EzReflect.init(snapshot.classLoader)
        packageName = snapshot.packageName
        processName = snapshot.processName
        isSystemServer = snapshot.isSystemServer
        targetSnapshot = snapshot
        val attempt = HotReloadAttempt()
        activeHotReloadAttempt.set(attempt)
        try {
            EzResources.restoreFromHotReload(TargetSnapshot.restoreResources(saved))
            onExtra?.accept(TargetSnapshot.restoreExtra(saved))
            if (onOldHooks != null) {
                // 自定义处置可能直接 unhook，库无法确认它在抛错前执行了哪些副作用。
                attempt.markIrreversible()
                onOldHooks.accept(XposedApiCompat.Api102.oldHookHandles(param))
            }
            dispatchTargetReady(propagateFailure = true)
            EzResources.commitHotReload()
            return true
        } catch (t: Throwable) {
            val failure = attempt.recover(t, EzResources::rollbackHotReload, EzResources::commitHotReload)
            recordHotReloadFailure(failure)
            throw failure
        } finally {
            activeHotReloadAttempt.remove()
        }
    }

    /**
     * 在 [HotReloadSession]、[HookReloadBatch] 或默认自动流程中注册 hook。
     *
     * session 优先要求每条 hook 使用显式 `reloadKey`；默认 [onTargetReady] 与手动 batch 会聚合未分配
     * hook ID 的 hook；事务外的 DSL/Java helper hook 才由 [AutomaticHookIdAllocator] 兜底分配内部 ID。
     * 调用 `HookFactory.id(null)` 可显式关闭自动分配，随后应使用自定义旧 handle 处置流程。
     *
     * framework 不支持 hook ID（API 101）时，这里直接安装不带 ID 的 hook；此时显式 `id` /
     * `reloadKey` 无法实现，会明确报错而不是静默降级成语义不同的匿名 hook。
     */
    internal fun installHookWithHotReloadTracking(
        target: Executable,
        priority: Int,
        exceptionMode: XposedInterface.ExceptionMode,
        id: String?,
        automaticIdEnabled: Boolean,
        hooker: XposedInterface.Hooker,
        installer: (String?, XposedInterface.Hooker) -> XposedInterface.HookHandle,
    ): XposedInterface.HookHandle {
        if (!XposedFeature.HOOK_ID.isSupported) {
            if (id != null) {
                XposedApiCompat.requireFeature(XposedFeature.HOOK_ID, "HookFactory.id / HookFactory.reloadKey")
            }
            return installer(null, hooker)
        }
        val session = activeHotReloadSession.get()
        if (session != null) {
            return session.installHook(target, id, hooker, installer)
        }
        val batch = HookReloadBatch.currentActive()
        if (batch != null) {
            return batch.installHook(
                target = target,
                priority = priority,
                exceptionMode = exceptionMode,
                hookId = id,
                automaticIdEnabled = automaticIdEnabled,
                hooker = hooker,
                installer = installer,
            )
        }
        // 关掉热重载后 automaticHookIds 为 null，不再兜底分配内部 ID：这些 ID 的唯一用途就是跨代
        // 识别 hook。显式声明的 id / reloadKey 仍然透传，HotReloadSession 与 HookReloadBatch 也照常可用。
        val effectiveId =
            id ?: automaticHookIds
                ?.takeIf { automaticIdEnabled }
                ?.allocate(target, priority, exceptionMode)
        markHotReloadIrreversible()
        return installer(effectiveId, hooker)
    }

    /** 让 [HotReloadSession] 暂时成为当前线程的 hook 安装上下文。 */
    internal fun <T> withHotReloadSession(
        session: HotReloadSession,
        block: () -> T,
    ): T {
        val previous = activeHotReloadSession.get()
        activeHotReloadSession.set(session)
        return try {
            block()
        } finally {
            if (previous == null) {
                activeHotReloadSession.remove()
            } else {
                activeHotReloadSession.set(previous)
            }
        }
    }

    /** 对方法或构造器做去优化；底层返回失败或发生异常时返回 false。需要异常原因时用 [deoptimizeOrThrow]。 */
    @JvmStatic
    fun deoptimize(executable: Executable): Boolean =
        runCatching {
            deoptimizeOrThrow(executable)
        }.getOrDefault(false)

    /** 原样返回框架的去优化结果；未初始化或框架调用异常会直接抛出。 */
    @JvmStatic
    fun deoptimizeOrThrow(executable: Executable): Boolean {
        check(::base.isInitialized) { "deoptimizeOrThrow requires EzXposed.initOnModuleLoaded first." }
        return base.deoptimize(executable)
    }

    /**
     * 停止当前 module entry 的后续生命周期回调。
     *
     * 调用后 framework 不会再向当前 entry 实例分发 `onPackageLoaded` / `onHotReloading` 等回调，
     * 已注册的 hook 与其它 [XposedInterface] API 不受影响。该方法幂等，可多次调用。
     *
     * 必须先在 `onModuleLoaded` 阶段调用 [initOnModuleLoaded] 并传入 [XposedInterfaceWrapper]（或其子类，
     * 例如 `XposedModule`）。如果传入的 `base` 不是 wrapper 类型，这里会抛出 [IllegalStateException]。
     */
    @JvmStatic
    @RequiresXposedApi(102)
    fun detachCurrentEntry() {
        val entry =
            moduleEntry ?: throw IllegalStateException(
                "detachCurrentEntry requires a XposedInterfaceWrapper (e.g. XposedModule) " +
                    "to be passed into EzXposed.initOnModuleLoaded.",
            )
        XposedApiCompat.requireFeature(XposedFeature.DETACH_ENTRY, "EzXposed.detachCurrentEntry")
        XposedApiCompat.Api102.detach(entry)
    }

    private fun getCurrentApplicationContext(): Context? =
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication =
                activityThreadClass
                    .getDeclaredMethod("currentApplication")
                    .apply {
                        isAccessible = true
                    }.invoke(null) as? Context
            currentApplication?.applicationContext ?: currentApplication
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Cannot get current application context.", e)
        }

    private fun requireModulePath(): String {
        if (::modulePath.isInitialized) return modulePath
        if (!::base.isInitialized) {
            throw IllegalStateException(
                "Cannot get modulePath before EzXposed.initOnModuleLoaded is called.",
            )
        }
        return base.moduleApplicationInfo.sourceDir.also { modulePath = it }
    }

    private fun createAutomaticHookBatch(
        base: XposedInterface,
        param: XposedModuleInterface.ModuleLoadedParam,
    ): HookReloadBatch =
        HookReloadBatch(
            namespace =
                "ezhooktool.default.v1:${base.moduleApplicationInfo.packageName}:" +
                    "${param.processName}:${param.isSystemServer}",
            xposed = base,
            requireStableTopologyOnHotReload = false,
        )

    private fun initializePackageTarget(
        targetPackageName: String,
        targetClassLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
    ) {
        EzReflect.init(targetClassLoader)
        packageName = targetPackageName
        targetSnapshot =
            TargetSnapshot(
                packageName = targetPackageName,
                processName = processName,
                classLoader = targetClassLoader,
                applicationInfo = applicationInfo,
                isSystemServer = false,
            )
    }

    private fun initializePackageTargetAndDispatch(
        targetPackageName: String,
        targetClassLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
    ) {
        if (!targetReadyDispatcher.start {
                initializePackageTarget(targetPackageName, targetClassLoader, applicationInfo)
            }
        ) {
            return
        }
        runTargetReadyCallbacks()
    }

    /** 触发一次就绪初始化；失败不会重放已经执行的回调。 */
    private fun dispatchTargetReady(propagateFailure: Boolean = false) {
        if (!targetReadyDispatcher.start()) {
            if (propagateFailure && targetReadyState == TargetReadyState.FAILED) {
                throw IllegalStateException("Target-ready initialization already failed.", targetReadyFailure)
            }
            return
        }
        runTargetReadyCallbacks(propagateFailure)
    }

    private fun runTargetReadyCallbacks(propagateFailure: Boolean = false) {
        val batch = automaticHookBatch?.takeIf { it.canStartInstall }
        targetReadyDispatcher.run(propagateFailure, batch?.let { it::install }) { t ->
            HookDiagnostics.error("EzXposed", "onTargetReady initialization failed", t)
        }
    }

    private fun runCallbackSafely(callback: TargetReadyCallback) {
        try {
            callback.run()
        } catch (t: Throwable) {
            HookDiagnostics.error("EzXposed", "onTargetReady callback failed", t)
        }
    }
}

/** 一次默认自动热重载成功完成后的实际替换与收尾统计。 */
data class AutomaticHotReloadResult(
    /** 新 generation 中由默认事务管理的逻辑 hook 与显式 hook 数量。 */
    val installedHookCount: Int,
    /** 通过相同 executable + hook ID 由 framework 原子替换的旧 hook 数量。 */
    val atomicallyReplacedHookCount: Int,
    /** 新代码未继续声明、在新 generation 安装成功后才 unhook 的旧 hook 数量。 */
    val removedOldHookCount: Int,
)

/**
 * [EzXposed.onTargetReady] 注册的回调。
 *
 * 用 `Runnable`-shape 的函数接口，Java 和 Kotlin lambda 均可。
 */
fun interface TargetReadyCallback {
    fun run()
}

/**
 * [EzXposed.runOnApplicationAttach] 注册的回调。
 *
 * 在目标进程 `Application.attach(Context)` 之后触发，参数为该 application context。
 */
fun interface ApplicationAttachCallback {
    fun onApplicationAttached(context: Context)
}

/**
 * 目标进程 snapshot。跨代时拍平成 `Array<Any?>`，全部字段都来自 system / app classloader，
 * 因此可安全塞进 [XposedModuleInterface.HotReloadingParam.setSavedInstanceState]。
 *
 * 序列化格式（`extra` 是使用者透传数据；可选 `resources` 是库管理的资源迁移状态）：
 *
 * ```
 * [MAGIC, VERSION, packageName, processName, classLoader, applicationInfo, isSystemServer, extra, resources?]
 * ```
 */
internal data class TargetSnapshot(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val applicationInfo: ApplicationInfo?,
    val isSystemServer: Boolean,
) {
    fun toCrossGenArray(
        extra: Array<Any?>,
        resources: Any? = null,
    ): Array<Any?> =
        arrayOf(
            MAGIC,
            VERSION,
            packageName,
            processName,
            classLoader,
            applicationInfo,
            isSystemServer,
            extra,
            resources,
        )

    companion object {
        private const val MAGIC = "EzXposed.TargetSnapshot"
        private const val VERSION = 2

        fun tryRestore(saved: Any?): TargetSnapshot? {
            val arr = saved as? Array<*> ?: return null
            if (arr.size < 8) return null
            if (arr[0] != MAGIC) return null
            if (arr[1] != VERSION) return null
            val packageName = arr[2] as? String ?: return null
            val processName = arr[3] as? String ?: return null
            val classLoader = arr[4] as? ClassLoader ?: return null
            val applicationInfo = arr[5] as? ApplicationInfo
            val isSystemServer = arr[6] as? Boolean ?: return null
            return TargetSnapshot(
                packageName = packageName,
                processName = processName,
                classLoader = classLoader,
                applicationInfo = applicationInfo,
                isSystemServer = isSystemServer,
            )
        }

        /** 读取 [toCrossGenArray] 的使用者透传数据；不是本库 snapshot 时返回空数组。 */
        fun restoreExtra(saved: Any?): Array<Any?> {
            val arr = saved as? Array<*> ?: return emptyArray()
            if (arr.size < 8 || arr[0] != MAGIC || arr[1] != VERSION) return emptyArray()
            @Suppress("UNCHECKED_CAST")
            return arr[7] as? Array<Any?> ?: emptyArray()
        }

        /** 第 9 位是 EzResources 的跨代状态；1.2.0 的数组只有 8 位，读不到就是 null。 */
        fun restoreResources(saved: Any?): Any? {
            val arr = saved as? Array<*> ?: return null
            if (arr.size < 9 || arr[0] != MAGIC || arr[1] != VERSION) return null
            return arr[8]
        }
    }
}

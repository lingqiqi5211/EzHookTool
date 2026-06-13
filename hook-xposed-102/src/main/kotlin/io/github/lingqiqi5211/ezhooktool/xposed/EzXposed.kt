package io.github.lingqiqi5211.ezhooktool.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterfaceWrapper
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.common.ModuleResources
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.unhookAll
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ApplicationLifecycle
import java.lang.reflect.Executable

/**
 * libxposed API 102 的运行时入口。
 *
 * 推荐初始化顺序：
 *
 * 1. 在 `XposedModule.onModuleLoaded` 里调用 [initOnModuleLoaded]
 * 2. 在 `XposedModule.onPackageLoaded` 里调用 [initOnPackageLoaded]
 * 3. 在 `XposedModule.onPackageReady` 里调用 [initOnPackageReady]
 *    或 `onSystemServerStarting` 里调用 [initOnSystemServerStarting]
 * 4. 通过 [onTargetReady] 注册「目标进程准备好后跑什么」
 *
 * 行为约定：
 *
 * - [base] 在 [initOnModuleLoaded] 后可用
 * - [classLoader] 在 [initOnPackageReady] 或 [initOnSystemServerStarting] 后代表当前进程反射环境
 * - [appContext] 采用懒解析，期望指向目标进程 application；如果应用尚未创建，请改用 [appContextOrNull] 或稍后访问
 * - [modulePath] / [moduleRes] 在 [initOnModuleLoaded] 后可用
 *
 * 热重载：见 [handleHotReloading] / [handleHotReloaded]。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak")
object EzXposed {
    private var appContextValue: Context? = null

    @JvmStatic
    /** libxposed 基础接口实例。 */
    lateinit var base: XposedInterface
        internal set

    private var moduleEntry: XposedInterfaceWrapper? = null

    /** 目标进程 snapshot；进入 [initOnPackageReady] / [initOnSystemServerStarting] 后填充。 */
    private var targetSnapshot: TargetSnapshot? = null

    /** [onTargetReady] 注册的回调列表。 */
    private val targetReadyCallbacks = mutableListOf<TargetReadyCallback>()

    @JvmStatic
    @Volatile
    /**
     * 是否启用安全模式。
     *
     * 开启后，hook 回调异常会被捕获、记日志并回退到原始调用。
     */
    var safeMode: Boolean = true

    @JvmStatic
    /** 当前包名。 */
    var packageName: String = ""
        private set

    @JvmStatic
    /** 当前进程名。 */
    var processName: String = ""
        private set

    @JvmStatic
    /** 当前是否运行在 `system_server`。 */
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

    @JvmStatic
    /** 当前模块资源；调用 [initOnModuleLoaded] 后可用。 */
    lateinit var moduleRes: Resources
        private set

    @JvmStatic
    /** 当前默认 `ClassLoader`。 */
    val classLoader: ClassLoader
        get() = EzReflect.classLoader

    @JvmStatic
    /** 始终可用的 `ClassLoader`；未初始化时回退到 `SystemClassLoader`。 */
    val safeClassLoader: ClassLoader
        get() = EzReflect.safeClassLoader

    @JvmStatic
    /** 当前进程的 application context；过早访问时会抛异常。 */
    val appContext: Context
        @Synchronized get() {
            appContextValue?.let { return it }

            val current = getCurrentApplicationContext()
                ?: throw NullPointerException(
                    "Cannot get appContext now, is Application onCreate finished?"
                )
            appContextValue = current
            return current
        }

    @JvmStatic
    /** 当前进程的 application context；尚未可用时返回 `null`。 */
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
     * 仍需覆盖现有缓存（极少见，例如热重载手动还原）时把 [force] 设为 `true`。
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
        val resolved = context ?: throw NullPointerException(
            "Cannot init appContext with null context."
        )
        synchronized(this) {
            if (force || appContextValue == null) {
                if (!force && resolved.applicationContext !== resolved) {
                    EzReflect.logger.warn(
                        "EzXposed",
                        "initAppContext received non-Application context " +
                                "(${resolved.javaClass.name}); using it as application cache. " +
                                "Prefer EzXposed.runOnApplicationAttach for the global application context."
                    )
                }
                appContextValue = resolved
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

    @JvmStatic
    /**
     * 初始化模块资源。
     *
     * 这个入口会读取 `base.moduleApplicationInfo.sourceDir` 作为模块 apk 路径，
     * 并创建可独立访问的 [moduleRes]。通常不需要手动调用；
     * [initOnModuleLoaded] 会自动初始化一次。
     */
    fun initModuleResources() {
        moduleRes = ModuleResources.create(requireModulePath())
    }

    @JvmStatic
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
        addAssetPathMethod.invoke(resources.assets, requireModulePath())
    }

    @JvmStatic
    /**
     * 在 `onModuleLoaded` 阶段初始化运行时基础信息。
     *
     * 这里会保存 libxposed 基础接口、进程元信息和模块资源，
     * 但不会初始化目标进程 [classLoader]。
     */
    fun initOnModuleLoaded(base: XposedInterface, param: XposedModuleInterface.ModuleLoadedParam) {
        // 热重载：framework 在调 onHotReloaded 前先调新 entry 的 onModuleLoaded。
        // 这里检测 base 实例变化即丢弃上一代 onTargetReady 回调与 targetSnapshot，
        // 防止旧 module classloader 通过这些 lambda 持续被强引用。
        if (::base.isInitialized && this.base !== base) {
            synchronized(targetReadyCallbacks) { targetReadyCallbacks.clear() }
            targetSnapshot = null
            appContextValue = null
        }
        this.base = base
        this.moduleEntry = base as? XposedInterfaceWrapper
        modulePath = base.moduleApplicationInfo.sourceDir
        initModuleResources()
        processName = param.processName
        isSystemServer = param.isSystemServer
    }

    @JvmStatic
    /** 在 `onPackageLoaded` 阶段记录当前包名。 */
    fun initOnPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        packageName = param.packageName
    }

    @JvmStatic
    /**
     * 在 `onPackageReady` 阶段初始化可直接用于反射的 [classLoader]。
     *
     * 从这个阶段开始，`findClass` / `findMethod` 一类 API 才应默认面向目标应用类使用。
     * 同时会建立目标进程 snapshot，触发已通过 [onTargetReady] 注册的回调。
     */
    fun initOnPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        EzReflect.init(param.classLoader)
        packageName = param.packageName
        targetSnapshot = TargetSnapshot(
            packageName = param.packageName,
            processName = processName,
            classLoader = param.classLoader,
            applicationInfo = param.applicationInfo,
            isSystemServer = false,
        )
        dispatchTargetReady()
    }

    @JvmStatic
    /**
     * 在 `onSystemServerStarting` 阶段初始化 `system_server` 的反射环境。
     *
     * 这个阶段通常没有常规意义上的应用上下文，因此不要默认依赖 [appContext]。
     * 同时会建立 system_server snapshot，触发已通过 [onTargetReady] 注册的回调。
     */
    fun initOnSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzReflect.init(param.classLoader)
        targetSnapshot = TargetSnapshot(
            packageName = packageName,
            processName = processName,
            classLoader = param.classLoader,
            applicationInfo = null,
            isSystemServer = true,
        )
        dispatchTargetReady()
    }

    /**
     * 注册「目标进程准备好后跑什么」的回调。
     *
     * 初次加载：在 [initOnPackageReady] / [initOnSystemServerStarting] 末尾触发。
     * 热重载：[handleHotReloaded] 还原 snapshot 后触发。
     *
     * 允许多次注册；按注册顺序执行。如果调用 [onTargetReady] 时目标进程已经就绪
     * （即 snapshot 已存在），新注册的回调会立即在当前线程执行一次，避免错过当前进程。
     *
     * @param callback 回调，可以从 [EzXposed.packageName] / [classLoader] / [isSystemServer] 读取当前上下文
     */
    @JvmStatic
    fun onTargetReady(callback: TargetReadyCallback) {
        synchronized(targetReadyCallbacks) {
            targetReadyCallbacks += callback
        }
        if (targetSnapshot != null) {
            runCallbackSafely(callback)
        }
    }

    /**
     * 在覆写的 `onHotReloading` 里直接调用。
     *
     * 把当前 [TargetSnapshot] 拍平成 `Array<Any?>` 透传给 `HotReloadingParam.setSavedInstanceState`，
     * 并返回 `true` 允许重载继续。如果当前还没进入 `onPackageReady` / `onSystemServerStarting`，
     * 会返回 `false`——此时没有可恢复的状态，重载没有意义。
     *
     * snapshot 数组的内容（`String`、`ClassLoader`、`ApplicationInfo`、`Boolean`、`Integer`）
     * 全部由 boot / system / app classloader 加载，符合 libxposed 102「setSavedInstanceState 不接受
     * 旧 module classloader 加载的对象」的硬约束。
     *
     * **返回值的含义由调用方 `onHotReloading` 的返回值传递给 framework**：
     * 返回 `true` 表示同意热重载；返回 `false` 表示模块自身要求 framework 放弃本次热重载请求。
     * 也就是说当本方法返回 `false` 时，请直接把它作为 `onHotReloading` 的返回值（默认写法即为 `return EzXposed.handleHotReloading(param)`）。
     *
     * 想自定义 saved state 的进阶用法直接覆写 `onHotReloading`，不要调用本方法。
     */
    @JvmStatic
    fun handleHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val snapshot = targetSnapshot ?: return false
        param.setSavedInstanceState(snapshot.toCrossGenArray())
        return true
    }

    /**
     * 在覆写的 `onHotReloaded` 里直接调用。
     *
     * 这里完成以下事情：
     *
     * 1. 用 [initOnModuleLoaded] 等价逻辑重置 [base]、[modulePath]、[moduleRes]、[processName]、[isSystemServer]。
     * 2. unhook 上一代全部 hook handle。
     * 3. 还原 [handleHotReloading] 透传的 snapshot，重建 [classLoader] 与 [packageName]。
     * 4. 触发已通过 [onTargetReady] 注册的回调，等价于一次 `onPackageReady` / `onSystemServerStarting`。
     *
     * 如果 saved state 不是由 [handleHotReloading] 生成的（例如使用者自己写了 `setSavedInstanceState`），
     * 这里只会完成步骤 1、2，不会触发 [onTargetReady]。
     */
    @JvmStatic
    fun handleHotReloaded(
        base: XposedInterface,
        param: XposedModuleInterface.HotReloadedParam,
    ) {
        initOnModuleLoaded(base, param)
        param.oldHookHandles.unhookAll()

        val snapshot = TargetSnapshot.tryRestore(param.savedInstanceState) ?: return
        EzReflect.init(snapshot.classLoader)
        packageName = snapshot.packageName
        processName = snapshot.processName
        isSystemServer = snapshot.isSystemServer
        targetSnapshot = snapshot
        dispatchTargetReady()
    }

    @JvmStatic
    /** 对方法或构造器做去优化。 */
    fun deoptimize(executable: Executable): Boolean = runCatching {
        base.deoptimize(executable)
        true
    }.getOrDefault(false)

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
    fun detachCurrentEntry() {
        val entry = moduleEntry ?: throw IllegalStateException(
            "detachCurrentEntry requires a XposedInterfaceWrapper (e.g. XposedModule) " +
                    "to be passed into EzXposed.initOnModuleLoaded."
        )
        entry.detach()
    }

    private fun getCurrentApplicationContext(): Context? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication").apply {
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
                "Cannot get modulePath before EzXposed.initOnModuleLoaded is called."
            )
        }
        return base.moduleApplicationInfo.sourceDir.also { modulePath = it }
    }

    private val addAssetPathMethod by lazy {
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
            isAccessible = true
        }
    }

    /** 触发当前已注册的 [onTargetReady] 回调。 */
    private fun dispatchTargetReady() {
        val snapshot = synchronized(targetReadyCallbacks) { targetReadyCallbacks.toList() }
        for (callback in snapshot) {
            runCallbackSafely(callback)
        }
    }

    private fun runCallbackSafely(callback: TargetReadyCallback) {
        try {
            callback.run()
        } catch (t: Throwable) {
            EzReflect.logger.error("EzXposed", "onTargetReady callback failed", t)
        }
    }
}

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
 * 序列化格式：
 *
 * ```
 * [MAGIC, VERSION, packageName, processName, classLoader, applicationInfo, isSystemServer]
 * ```
 */
internal data class TargetSnapshot(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val applicationInfo: ApplicationInfo?,
    val isSystemServer: Boolean,
) {
    fun toCrossGenArray(): Array<Any?> = arrayOf(
        MAGIC,
        VERSION,
        packageName,
        processName,
        classLoader,
        applicationInfo,
        isSystemServer,
    )

    companion object {
        private const val MAGIC = "EzXposed.TargetSnapshot"
        private const val VERSION = 1

        fun tryRestore(saved: Any?): TargetSnapshot? {
            val arr = saved as? Array<*> ?: return null
            if (arr.size < 7) return null
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
    }
}

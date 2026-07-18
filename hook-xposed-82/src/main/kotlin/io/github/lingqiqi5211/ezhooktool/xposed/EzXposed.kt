package io.github.lingqiqi5211.ezhooktool.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XModuleResources
import android.content.res.XResources
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ApplicationLifecycle

/**
 * 经典 Xposed API 82 的运行时入口。
 *
 * 推荐初始化顺序：
 *
 * 1. 在 `IXposedHookZygoteInit.initZygote` 里调用 [initZygote]
 * 2. 在 `IXposedHookLoadPackage.handleLoadPackage` 里调用 [init]
 *
 * [initZygote] 会自动初始化模块资源；application context 仍按需懒解析，
 * 也可以由调用方显式触发 [initAppContext]。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak")
object EzXposed {
    private var appContextValue: Context? = null

    @JvmStatic
    @Volatile
    /** 是否启用安全模式。 */
    var safeMode: Boolean = true

    @JvmStatic
    /** 当前包名；`init(lpparam)` 之前为空字符串。 */
    var packageName: String = ""
        private set

    @JvmStatic
    /** 当前进程名；`init(lpparam)` 之前为空字符串。 */
    var processName: String = ""
        private set

    @JvmStatic
    /** 当前模块 apk 路径；调用 [initZygote] 后可用。 */
    lateinit var modulePath: String
        private set

    @JvmStatic
    /** 当前模块资源；调用 [initZygote] 后可用。 */
    lateinit var moduleRes: XModuleResources
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
     * 仍需覆盖现有缓存时把 [force] 设为 `true`。
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
     * 仅依赖 `XposedBridge.hookMethod`，无需先调用 [initZygote]，因此在 `handleLoadPackage` 阶段就可以注册。
     *
     * 与 102 的差异：102 必须先调 `initOnModuleLoaded` 拿到 `XposedInterface.base` 后才能 hook，因此 102 上有一道
     * `IllegalStateException` 检查；82 没有该限制。跨工件共用代码请按 102 的契约走。
     */
    @JvmStatic
    fun runOnApplicationAttach(callback: ApplicationAttachCallback) {
        ApplicationLifecycle.register(callback)
    }

    /**
     * 仅供 [ApplicationLifecycle] 内部回填：在 `Application.attach` 触发瞬间把 application context 写进缓存。
     */
    internal fun cacheApplicationContextFromLifecycle(context: Context) {
        synchronized(this) {
            if (appContextValue == null) {
                appContextValue = context
            }
        }
    }

    @JvmStatic
    /** 在 `IXposedHookZygoteInit.initZygote` 阶段记录模块 apk 路径并初始化模块资源。 */
    fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        initModuleResources()
    }

    @JvmStatic
    /**
     * 初始化模块资源。
     *
     * 默认会创建独立的 [XModuleResources]；
     * 如需复用资源配置，可显式传入 [origRes]。
     * 通常不需要手动调用；[initZygote] 会自动初始化一次。
     */
    fun initModuleResources(origRes: XResources? = null) {
        moduleRes = XModuleResources.createInstance(requireModulePath(), origRes)
    }

    @JvmStatic
    /**
     * 添加模块路径到目标 `Context.resources`。允许通过“R.xx.xxx”直接使用模块资源。
     *
     * 如果您想使用它，请执行以下操作：
     *
     * 1. 修改资源 ID，使其不与挂钩的应用程序或其他 Xposed 模块冲突。
     *
     *  Kotlin Gradle DSL：
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

    @JvmStatic
    /** 将模块资源路径注入到指定 [resources]。 */
    fun addModuleAssetPath(resources: Resources) {
        addAssetPathMethod.invoke(resources.assets, requireModulePath())
    }

    @JvmStatic
    /** 使用 `LoadPackageParam` 初始化当前进程的反射环境和进程信息。 */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzReflect.init(lpparam.classLoader)
        packageName = lpparam.packageName
        processName = lpparam.processName
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
        throw IllegalStateException(
            "Cannot get modulePath before EzXposed.initZygote is called."
        )
    }

    private val addAssetPathMethod by lazy {
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
            isAccessible = true
        }
    }
}

/**
 * [EzXposed.runOnApplicationAttach] 注册的回调。
 *
 * 在目标进程 `Application.attach(Context)` 之后触发，参数为该 application context。
 */
fun interface ApplicationAttachCallback {
    fun onApplicationAttached(context: Context)
}

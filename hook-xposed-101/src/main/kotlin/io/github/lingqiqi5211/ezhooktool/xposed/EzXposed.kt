package io.github.lingqiqi5211.ezhooktool.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.common.ModuleResources
import java.lang.reflect.Executable

/**
 * libxposed API 101 的运行时入口。
 *
 * 推荐初始化顺序：
 *
 * 1. 在 `XposedModule.onModuleLoaded` 里调用 [initOnModuleLoaded]
 * 2. 如果需要模块资源，调用 [initModuleResources]
 * 3. 在 `XposedModule.onPackageLoaded` 里调用 [initOnPackageLoaded]
 * 4. 在 `XposedModule.onPackageReady` 里调用 [initOnPackageReady]
 *
 * 行为约定：
 *
 * - [base] 在 [initOnModuleLoaded] 后可用
 * - [classLoader] 在 [initOnPackageReady] 或 [initOnSystemServerStarting] 后代表当前进程反射环境
 * - [appContext] 采用懒解析；如果应用尚未创建，请改用 [appContextOrNull] 或稍后访问
 * - [modulePath] / [moduleRes] 不会自动初始化；只有显式调用 [initModuleResources] 后才可用
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak")
object EzXposed {
    private var appContextValue: Context? = null

    @JvmStatic
    /** libxposed 基础接口实例。 */
    lateinit var base: XposedInterface
        internal set

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
    /** 当前模块 apk 路径；调用 [initModuleResources] 后可用。 */
    lateinit var modulePath: String
        private set

    @JvmStatic
    /** 当前模块资源；调用 [initModuleResources] 后可用。 */
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
     * 默认会尝试复用当前运行时里的 application；
     * 如需让目标进程资源对象同时挂上模块资源路径，可把 [injectModuleAssetPath] 设为 `true`。
     */
    fun initAppContext(
        context: Context? = getCurrentApplicationContext(),
        injectModuleAssetPath: Boolean = false,
    ) {
        val resolved = context ?: throw NullPointerException(
            "Cannot init appContext with null context."
        )
        appContextValue = resolved
        if (injectModuleAssetPath) {
            addModuleAssetPath(resolved)
        }
    }

    @JvmStatic
    /**
     * 初始化模块资源。
     *
     * 这个入口会读取 `base.moduleApplicationInfo.sourceDir` 作为模块 apk 路径，
     * 并创建可独立访问的 [moduleRes]。
     */
    fun initModuleResources() {
        modulePath = base.moduleApplicationInfo.sourceDir
        moduleRes = ModuleResources.create(modulePath)
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

    @JvmStatic
    fun addModuleAssetPath(resources: Resources) {
        addAssetPathMethod.invoke(resources.assets, modulePath)
    }

    @JvmStatic
    /**
     * 在 `onModuleLoaded` 阶段初始化运行时基础信息。
     *
     * 这里只保存 libxposed 基础接口和进程元信息，
     * 不会自动初始化 [moduleRes] 或目标进程 [classLoader]。
     */
    fun initOnModuleLoaded(base: XposedInterface, param: XposedModuleInterface.ModuleLoadedParam) {
        this.base = base
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
     */
    fun initOnPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        EzReflect.init(param.classLoader)
        packageName = param.packageName
    }

    @JvmStatic
    /**
     * 在 `onSystemServerStarting` 阶段初始化 `system_server` 的反射环境。
     *
     * 这个阶段通常没有常规意义上的应用上下文，因此不要默认依赖 [appContext]。
     */
    fun initOnSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzReflect.init(param.classLoader)
    }

    @JvmStatic
    /** 对方法或构造器做去优化。 */
    fun deoptimize(executable: Executable): Boolean = runCatching {
        base.deoptimize(executable)
        true
    }.getOrDefault(false)

    private fun getCurrentApplicationContext(): Context? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication").apply {
            isAccessible = true
        }.invoke(null) as? Context
        currentApplication?.applicationContext ?: currentApplication
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException("Cannot get current application context.", e)
    }

    private val addAssetPathMethod by lazy {
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
            isAccessible = true
        }
    }
}

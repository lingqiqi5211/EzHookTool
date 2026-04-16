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

/**
 * 经典 Xposed API 82 的运行时入口。
 *
 * 推荐初始化顺序：
 *
 * 1. 如果需要模块资源，在 `IXposedHookZygoteInit.initZygote` 里调用 [initZygote]
 * 2. 如果需要模块资源，调用 [initModuleResources]
 * 3. 在 `IXposedHookLoadPackage.handleLoadPackage` 里调用 [init]
 *
 * 和上游保持一致，这里不会自动初始化模块资源或 application context，
 * 需要由调用方按生命周期显式触发对应入口。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak")
object EzXposed {
    private var appContextValue: Context? = null

    @JvmStatic
    @Volatile
    /** 是否启用安全模式。 */
    var safeMode: Boolean = true

    @JvmStatic
    /** 当前包名。 */
    lateinit var packageName: String
        private set

    @JvmStatic
    /** 当前进程名。 */
    lateinit var processName: String
        private set

    @JvmStatic
    /** 当前模块 apk 路径；调用 [initZygote] 后可用。 */
    lateinit var modulePath: String
        private set

    @JvmStatic
    /** 当前模块资源；调用 [initModuleResources] 后可用。 */
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
     * 若需要把模块资源路径注入到目标应用资源，请显式打开 [injectModuleAssetPath]。
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
    /** 在 `IXposedHookZygoteInit.initZygote` 阶段记录模块 apk 路径。 */
    fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    @JvmStatic
    /**
     * 初始化模块资源。
     *
     * 默认会创建独立的 [XModuleResources]；
     * 如需复用资源配置，可显式传入 [origRes]。
     */
    fun initModuleResources(origRes: XResources? = null) {
        moduleRes = XModuleResources.createInstance(modulePath, origRes)
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
        addAssetPathMethod.invoke(resources.assets, modulePath)
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

    private val addAssetPathMethod by lazy {
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
            isAccessible = true
        }
    }
}

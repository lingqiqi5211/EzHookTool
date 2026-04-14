package io.github.lingqiqi5211.ezhooktool.xposed

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.internal.AppContextProvider
import java.lang.reflect.Executable

/** libxposed 101 运行时入口。 */
object EzXposed {
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
    /** 当前是否运行在 system_server。 */
    var isSystemServer: Boolean = false
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
        get() = AppContextProvider.requireCurrent()

    @JvmStatic
    /** 当前进程的 application context；尚未可用时返回 `null`。 */
    val appContextOrNull: Context?
        get() = AppContextProvider.currentOrNull()

    @JvmStatic
    /** 手动初始化 application context。 */
    fun initAppContext(context: Context? = AppContextProvider.currentOrNull()) {
        AppContextProvider.init(context)
    }

    @JvmStatic
    /** 在模块加载阶段记录运行时基础信息。 */
    fun initOnModuleLoaded(base: XposedInterface, param: XposedModuleInterface.ModuleLoadedParam) {
        this.base = base
        processName = param.processName
        isSystemServer = param.isSystemServer
    }

    @JvmStatic
    /** 在目标包加载阶段记录包名并初始化默认 `ClassLoader`。 */
    fun initOnPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        EzReflect.init(param.defaultClassLoader)
        packageName = param.packageName
    }

    @JvmStatic
    /** 在目标包 ready 阶段初始化 `classLoader` 并记录包名。 */
    fun initOnPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        EzReflect.init(param.classLoader)
        packageName = param.packageName
    }

    @JvmStatic
    /** 在 system_server 启动阶段初始化 `classLoader`。 */
    fun initOnSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzReflect.init(param.classLoader)
    }

    @JvmStatic
    /** 对方法或构造器做去优化。 */
    fun deoptimize(executable: Executable): Boolean = runCatching {
        base.deoptimize(executable)
        true
    }.getOrDefault(false)
}

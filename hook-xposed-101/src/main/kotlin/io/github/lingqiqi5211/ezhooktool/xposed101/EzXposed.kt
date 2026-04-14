package io.github.lingqiqi5211.ezhooktool.xposed101

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import java.lang.reflect.Executable

/**
 * libxposed 101 运行时入口。
 *
 * 负责记录模块和目标包状态，并把可用的 `classLoader` 交给 [EzReflect]。
 */
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
     * 开启后，hook 回调异常会被捕获并回退到原始调用。
     */
    var safeMode: Boolean = true

    @JvmStatic
    /** 当前目标包名。 */
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
    /** 当前 libxposed 上下文绑定的默认 `ClassLoader`。 */
    val classLoader: ClassLoader
        get() = EzReflect.classLoader

    @JvmStatic
    /**
     * 在模块加载阶段初始化运行时基础信息。
     *
     * @param base libxposed 提供的基础接口
     * @param param 当前模块加载阶段的上下文参数
     */
    fun initOnModuleLoaded(base: XposedInterface, param: XposedModuleInterface.ModuleLoadedParam) {
        this.base = base
        processName = param.processName
        isSystemServer = param.isSystemServer
    }

    @JvmStatic
    /**
     * 在目标包加载阶段记录包名。
     *
     * @param param 当前包加载阶段的上下文参数
     */
    fun initOnPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        packageName = param.packageName
    }

    @JvmStatic
    /**
     * 在目标包准备阶段初始化 `classLoader` 并记录包名。
     *
     * @param param 当前包 ready 阶段的上下文参数
     */
    fun initOnPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        EzReflect.init(param.classLoader)
        packageName = param.packageName
    }

    @JvmStatic
    /**
     * 在 system_server 启动阶段初始化 `classLoader`。
     *
     * @param param system_server 启动阶段的上下文参数
     */
    fun initOnSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzReflect.init(param.classLoader)
    }

    @JvmStatic
    /**
     * 对指定可执行成员做去优化处理。
     *
     * @param executable 目标方法或构造器
     */
    fun deoptimize(executable: Executable): Boolean = runCatching {
        base.deoptimize(executable)
        true
    }.getOrDefault(false)
}

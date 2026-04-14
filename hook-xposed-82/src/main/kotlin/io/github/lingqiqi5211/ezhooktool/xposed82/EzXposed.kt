package io.github.lingqiqi5211.ezhooktool.xposed82

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lingqiqi5211.ezhooktool.core.EzReflect

/**
 * Xposed 82 运行时入口。
 *
 * 负责记录当前包信息，并把 Xposed 提供的 `classLoader` 交给 [EzReflect]。
 */
object EzXposed {
    @JvmStatic
    @Volatile
    /**
     * 是否启用安全模式。
     *
     * 开启后，hook 回调中的异常会被捕获并记日志，避免直接影响目标进程。
     */
    var safeMode: Boolean = true

    @JvmStatic
    /** 当前正在处理的包名。调用 [init] 后可用。 */
    lateinit var packageName: String
        private set

    @JvmStatic
    /** 当前正在处理的进程名。调用 [init] 后可用。 */
    lateinit var processName: String
        private set

    @JvmStatic
    /** 当前 Xposed 运行时绑定的默认 `ClassLoader`。 */
    val classLoader: ClassLoader
        get() = EzReflect.classLoader

    @JvmStatic
    /**
     * 使用 `LoadPackageParam` 初始化 Xposed 82 运行时上下文。
     *
     * @param lpparam Xposed 在 `handleLoadPackage` 阶段提供的运行时参数
     */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzReflect.init(lpparam.classLoader)
        packageName = lpparam.packageName
        processName = lpparam.processName
    }
}

package io.github.lingqiqi5211.ezhooktool.xposed

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.internal.AppContextProvider

/** Xposed 82 运行时入口。 */
object EzXposed {
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
    lateinit var packageName: String
        private set

    @JvmStatic
    /** 当前进程名。 */
    lateinit var processName: String
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
    /** 使用 `LoadPackageParam` 初始化 Xposed 82 运行时上下文。 */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzReflect.init(lpparam.classLoader)
        packageName = lpparam.packageName
        processName = lpparam.processName
    }
}

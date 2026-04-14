package io.github.lingqiqi5211.ezhooktool.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 日志接口。
 *
 * core 模块默认使用 [DefaultLogger]（输出到 System.err）。
 * Android 环境可替换为 android.util.Log 实现。
 *
 * ```kotlin
 * EzReflect.logger = object : EzLogger {
 *     override fun debug(tag: String, msg: String) { Log.d(tag, msg) }
 *     override fun warn(tag: String, msg: String) { Log.w(tag, msg) }
 *     override fun error(tag: String, msg: String, t: Throwable?) { Log.e(tag, msg, t) }
 * }
 * ```
 */
interface EzLogger {
    /**
     * 输出调试日志。
     *
     * @param tag 日志标签
     * @param msg 日志内容
     */
    fun debug(tag: String, msg: String)

    /**
     * 输出警告日志。
     *
     * @param tag 日志标签
     * @param msg 日志内容
     */
    fun warn(tag: String, msg: String)

    /**
     * 输出错误日志，并可附带异常。
     *
     * @param tag 日志标签
     * @param msg 日志内容
     * @param t 可选异常对象
     */
    fun error(tag: String, msg: String, t: Throwable? = null)
}

/** 默认日志实现，输出到 System.err。 */
internal object DefaultLogger : EzLogger {
    override fun debug(tag: String, msg: String) {
        System.err.println("[$tag] D: $msg")
    }

    override fun warn(tag: String, msg: String) {
        System.err.println("[$tag] W: $msg")
    }

    override fun error(tag: String, msg: String, t: Throwable?) {
        System.err.println("[$tag] E: $msg")
        t?.printStackTrace(System.err)
    }
}

/**
 * EzHookTool 反射核心初始化入口。
 *
 * 所有反射 API 的 classLoader 参数默认使用 [classLoader]。
 * 不调用 [init] 时，默认使用 [ClassLoader.getSystemClassLoader]。
 *
 * ```kotlin
 * // Xposed 中
 * EzReflect.init(lpparam.classLoader)
 *
 * // 纯 JVM 中
 * EzReflect.init(MyClass::class.java.classLoader!!)
 * ```
 */
object EzReflect {

    private const val TAG = "EzReflect"

    @Volatile
    private var _initialized = false

    /**
     * 当前全局默认 ClassLoader。
     *
     * 所有反射查找 API 的 classLoader 参数默认使用此值。
     * 调用 [init] 设置，未初始化时为 [ClassLoader.getSystemClassLoader]。
     */
    @Volatile
    var classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        private set

    /** 是否已调用过 [init]。 */
    val isInitialized: Boolean get() = _initialized

    /**
     * 是否启用查找结果缓存。默认 true。
     *
     * 开启后，findMethod / findField / findConstructor 的结果会以
     * (Class + 条件参数) 为 key 缓存，避免重复遍历 declaredMembers。
     *
     * 关闭缓存：`EzReflect.cacheEnabled = false`
     * 清除已有缓存：`EzReflect.clearCache()`
     */
    @Volatile
    @JvmStatic
    var cacheEnabled: Boolean = true

    /**
     * 调试模式。默认 false。
     *
     * 开启后，[MemberNotFoundException] 的错误信息会包含候选成员列表，
     * 方便排查条件写错的问题。
     *
     * ```kotlin
     * EzReflect.debugMode = true
     * ```
     */
    @Volatile
    @JvmStatic
    var debugMode: Boolean = false

    /**
     * 日志实现。默认 [DefaultLogger]（输出到 System.err）。
     *
     * ```kotlin
     * EzReflect.logger = MyAndroidLogger()
     * ```
     */
    @Volatile
    @JvmStatic
    var logger: EzLogger = DefaultLogger

    @Volatile
    @JvmStatic
    /**
     * 成员枚举策略。
     *
     * 默认直接读取 `declaredMembers`，如需兼容特殊运行时或自定义过滤逻辑，可替换为自己的实现。
     */
    var memberResolver: MemberResolver = DefaultMemberResolver

    /** 内部查找缓存。 */
    @PublishedApi
    internal val cache = ConcurrentHashMap<Any, Any>()

    /**
     * 初始化默认 ClassLoader。
     *
     * 通常在 Xposed handleLoadPackage 中调用:
     * ```kotlin
     * EzReflect.init(lpparam.classLoader)
     * ```
     *
     * @param classLoader 要设置为全局默认值的 `ClassLoader`
     */
    @JvmStatic
    fun init(classLoader: ClassLoader) {
        this.classLoader = classLoader
        _initialized = true
        logger.debug(TAG, "Initialized with classLoader: $classLoader")
    }

    /** 清除查找结果缓存。 */
    @JvmStatic
    fun clearCache() {
        cache.clear()
        logger.debug(TAG, "Cache cleared")
    }

    /** 重置为 SystemClassLoader，清除初始化状态和缓存。 */
    @JvmStatic
    fun reset() {
        classLoader = ClassLoader.getSystemClassLoader()
        _initialized = false
        cache.clear()
        logger.debug(TAG, "Reset to default state")
    }
}

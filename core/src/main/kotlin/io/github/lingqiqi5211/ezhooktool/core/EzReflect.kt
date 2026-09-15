package io.github.lingqiqi5211.ezhooktool.core

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** 日志接口；core 默认输出到 System.err，Android 调用方可替换实现。 */
interface EzLogger {
    /** 输出调试日志。 */
    fun debug(
        tag: String,
        msg: String,
    )

    /** 输出警告日志。 */
    fun warn(
        tag: String,
        msg: String,
    )

    /** 输出错误日志，并可附带异常。 */
    fun error(
        tag: String,
        msg: String,
        t: Throwable? = null,
    )
}

/** 默认日志实现，输出到 System.err。 */
internal object DefaultLogger : EzLogger {
    override fun debug(
        tag: String,
        msg: String,
    ) {
        System.err.println("[$tag] D: $msg")
    }

    override fun warn(
        tag: String,
        msg: String,
    ) {
        System.err.println("[$tag] W: $msg")
    }

    override fun error(
        tag: String,
        msg: String,
        t: Throwable?,
    ) {
        System.err.println("[$tag] E: $msg")
        t?.printStackTrace(System.err)
    }
}

internal enum class ReflectCacheBucket { METHOD, FIELD, CONSTRUCTOR }

/**
 * 反射核心入口，无需 Android 或 Hook 运行时。
 * 未初始化时使用 SystemClassLoader；[init]、[reset]、[clearCache] 和解析器变更会发布新的配置/缓存状态。
 * 同步查询及其嵌套查询使用同一状态，在途旧查询不能向新缓存写入结果。
 */
object EzReflect {
    private const val TAG = "EzReflect"
    internal val context = ReflectionContext()

    /** 默认参数占位，不作为真实加载器使用；进入查询状态后才解析当前默认 loader。 */
    @PublishedApi
    internal val defaultLoaderMarker: ClassLoader = object : ClassLoader(null) {}

    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withQuery(block: () -> T): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return context.query(block)
    }

    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withQuery(
        classLoader: ClassLoader,
        block: (ClassLoader) -> T,
    ): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return context.query {
            block(if (classLoader === defaultLoaderMarker) context.state.classLoader else classLoader)
        }
    }

    /** 当前默认 ClassLoader；查询内部读取的是该次查询捕获的配置。 */
    var classLoader: ClassLoader
        get() = context.state.classLoader
        private set(value) {
            context.update { it.copy(classLoader = value) }
        }

    /** 始终可用的 ClassLoader，未初始化时为 SystemClassLoader。 */
    @JvmStatic
    val safeClassLoader: ClassLoader get() = classLoader

    /** 是否已调用 [init]。 */
    val isInitialized: Boolean get() = context.state.initialized

    /**
     * 是否缓存查找结果，默认 true。
     * 当前作用域的成员和类查询合计最多 4096 条，超过 256 项的集合结果不缓存。
     * 缓存有效期间强引用键及结果；[init]、[reset]、[clearCache] 或配置切换会清空退休状态并停止其写入。
     * 自定义 filter 默认不自动缓存；主动 cache key 的一致性由调用者负责。
     */
    @JvmStatic
    var cacheEnabled: Boolean
        get() = context.state.cacheEnabled
        set(value) {
            context.update { it.copy(cacheEnabled = value) }
        }

    /** 开启后查找失败信息包含候选成员。 */
    @Volatile
    @JvmStatic
    var debugMode: Boolean = false

    /** 日志实现，默认 [DefaultLogger]。 */
    @Volatile
    @JvmStatic
    var logger: EzLogger = DefaultLogger

    /** 成员枚举策略；替换时与缓存一起发布，在途查询继续使用原策略。 */
    @JvmStatic
    var memberResolver: MemberResolver
        get() = context.state.memberResolver
        set(value) {
            context.update { it.copy(memberResolver = value) }
        }

    /** 类名枚举策略；默认返回空序列，可接入平台索引，替换时发布新的缓存状态。 */
    @JvmStatic
    var classResolver: ClassResolver
        get() = context.state.classResolver
        set(value) {
            context.update { it.copy(classResolver = value) }
        }

    internal fun cacheGet(
        owner: Class<*>,
        bucket: ReflectCacheBucket,
        key: Any,
    ): Any? = context.state.get(owner, bucket, key)

    internal fun cachePut(
        owner: Class<*>,
        bucket: ReflectCacheBucket,
        key: Any,
        value: Any,
    ) = context.state.put(owner, bucket, key, value)

    internal fun classCacheGet(
        classLoader: ClassLoader,
        key: Any,
    ): Class<*>? = context.state.get(classLoader, null, key) as? Class<*>

    internal fun classCachePut(
        classLoader: ClassLoader,
        key: Any,
        value: Class<*>,
    ) = context.state.put(classLoader, null, key, value)

    internal fun classQueryCacheGet(
        classLoader: ClassLoader,
        key: Any,
    ): Any? = context.state.get(classLoader, null, key)

    internal fun classQueryCachePut(
        classLoader: ClassLoader,
        key: Any,
        value: Any,
    ) = context.state.put(classLoader, null, key, value)

    /** 设置默认 ClassLoader，建立新的运行作用域；不改变已配置的解析器。 */
    @JvmStatic
    fun init(classLoader: ClassLoader) {
        context.update { it.copy(classLoader = classLoader, initialized = true) }
        logger.debug(TAG, "Initialized with classLoader: $classLoader")
    }

    /** 切换到同配置的新缓存；旧查询完成后不会重新填入清空后的缓存。 */
    @JvmStatic
    fun clearCache() {
        context.update { it.copy() }
        logger.debug(TAG, "Cache cleared")
    }

    /** 重置默认加载器和初始化状态，释放当前缓存；保留解析器等显式配置。 */
    @JvmStatic
    fun reset() {
        context.update { it.copy(classLoader = ClassLoader.getSystemClassLoader(), initialized = false) }
        logger.debug(TAG, "Reset to default state")
    }
}

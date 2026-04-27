package io.github.lingqiqi5211.ezhooktool.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

internal enum class ReflectCacheBucket {
    METHOD,
    FIELD,
    CONSTRUCTOR,
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
    private const val CACHE_TRIM_PUT_INTERVAL = 64
    private const val CACHE_STALE_ACCESS_GAP = 4096L

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

    /**
     * 始终可用的 `ClassLoader`。
     *
     * 未初始化时会回退到 `SystemClassLoader`。
     */
    @JvmStatic
    val safeClassLoader: ClassLoader
        get() = if (_initialized) classLoader else ClassLoader.getSystemClassLoader()

    /** 是否已调用过 [init]。 */
    val isInitialized: Boolean get() = _initialized

    /**
     * 是否启用查找结果缓存。默认 true。
     *
     * 开启后，findClass / findMethod / findField / findConstructor 的结果会以稳定查询条件为 key 缓存，
     * 减少重复加载类和重复遍历成员列表。
     *
     * `name`、`paramCount`、`type`、`params` 等结构化条件会参与缓存。
     * 自定义 `filter` 可能捕获外部状态，不会参与缓存，避免错误复用旧结果。
     * 缓存仅保存在当前运行期内，会在 [init]、[reset] 或 [clearCache] 时清空。
     *
     * 关闭缓存并释放已有缓存：`EzReflect.cacheEnabled = false`
     * 清除已有缓存：`EzReflect.clearCache()`
     */
    @Volatile
    @JvmStatic
    var cacheEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) clearCache()
        }

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

    /**
     * 成员枚举策略。
     *
     * 默认直接读取 `declaredMembers`，如需兼容特殊运行时或自定义过滤逻辑，可替换为自己的实现。
     * 替换策略时会清空查找缓存，避免复用旧策略下的结果。
     */
    @Volatile
    @JvmStatic
    var memberResolver: MemberResolver = DefaultMemberResolver
        set(value) {
            field = value
            clearCache()
        }

    private data class CacheEntry(
        val value: Any,
        @Volatile var lastAccess: Long,
    )

    private class MemberClassCache {
        val methods = ConcurrentHashMap<Any, CacheEntry>()
        val fields = ConcurrentHashMap<Any, CacheEntry>()
        val constructors = ConcurrentHashMap<Any, CacheEntry>()
        private val putCount = AtomicInteger()

        fun bucket(type: ReflectCacheBucket): ConcurrentHashMap<Any, CacheEntry> = when (type) {
            ReflectCacheBucket.METHOD -> methods
            ReflectCacheBucket.FIELD -> fields
            ReflectCacheBucket.CONSTRUCTOR -> constructors
        }

        fun shouldTrimAfterPut(): Boolean =
            putCount.incrementAndGet() % CACHE_TRIM_PUT_INTERVAL == 0
    }

    private class ClassLoaderCache {
        val classes = ConcurrentHashMap<Any, CacheEntry>()
        private val putCount = AtomicInteger()

        fun shouldTrimAfterPut(): Boolean =
            putCount.incrementAndGet() % CACHE_TRIM_PUT_INTERVAL == 0
    }

    private val memberCache = ConcurrentHashMap<Class<*>, MemberClassCache>()
    private val classCache = ConcurrentHashMap<ClassLoader, ClassLoaderCache>()
    private val cacheClock = AtomicLong()

    internal fun cacheGet(owner: Class<*>, bucket: ReflectCacheBucket, key: Any): Any? {
        if (!cacheEnabled) return null
        val entry = memberCache[owner]?.bucket(bucket)?.get(key) ?: return null
        entry.lastAccess = cacheClock.incrementAndGet()
        return entry.value
    }

    internal fun cachePut(owner: Class<*>, bucket: ReflectCacheBucket, key: Any, value: Any) {
        if (!cacheEnabled) return
        val tick = cacheClock.incrementAndGet()
        val ownerCache = memberCache.computeIfAbsent(owner) { MemberClassCache() }
        ownerCache.bucket(bucket)[key] = CacheEntry(value, tick)
        if (ownerCache.shouldTrimAfterPut()) {
            trimColdEntries(ownerCache, tick)
        }
    }

    internal fun classCacheGet(classLoader: ClassLoader, key: Any): Class<*>? {
        if (!cacheEnabled) return null
        val entry = classCache[classLoader]?.classes?.get(key) ?: return null
        entry.lastAccess = cacheClock.incrementAndGet()
        return entry.value as? Class<*>
    }

    internal fun classCachePut(classLoader: ClassLoader, key: Any, value: Class<*>) {
        if (!cacheEnabled) return
        val tick = cacheClock.incrementAndGet()
        val loaderCache = classCache.computeIfAbsent(classLoader) { ClassLoaderCache() }
        loaderCache.classes[key] = CacheEntry(value, tick)
        if (loaderCache.shouldTrimAfterPut()) {
            trimColdEntries(loaderCache, tick)
        }
    }

    private fun trimColdEntries(classCache: MemberClassCache, currentTick: Long) {
        val staleBefore = currentTick - CACHE_STALE_ACCESS_GAP
        if (staleBefore <= 0) return
        trimColdEntries(classCache.methods, staleBefore)
        trimColdEntries(classCache.fields, staleBefore)
        trimColdEntries(classCache.constructors, staleBefore)
    }

    private fun trimColdEntries(classCache: ClassLoaderCache, currentTick: Long) {
        val staleBefore = currentTick - CACHE_STALE_ACCESS_GAP
        if (staleBefore <= 0) return
        trimColdEntries(classCache.classes, staleBefore)
    }

    private fun trimColdEntries(bucket: ConcurrentHashMap<Any, CacheEntry>, staleBefore: Long) {
        for ((key, entry) in bucket) {
            if (entry.lastAccess <= staleBefore) {
                bucket.remove(key, entry)
            }
        }
    }

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
        clearCache()
        logger.debug(TAG, "Initialized with classLoader: $classLoader")
    }

    /** 清除查找结果缓存。 */
    @JvmStatic
    fun clearCache() {
        memberCache.clear()
        classCache.clear()
        cacheClock.set(0)
        logger.debug(TAG, "Cache cleared")
    }

    /** 重置为 SystemClassLoader，清除初始化状态和缓存。 */
    @JvmStatic
    fun reset() {
        classLoader = ClassLoader.getSystemClassLoader()
        _initialized = false
        clearCache()
        logger.debug(TAG, "Reset to default state")
    }
}

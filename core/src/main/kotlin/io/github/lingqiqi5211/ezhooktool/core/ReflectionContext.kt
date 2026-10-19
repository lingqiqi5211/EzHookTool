package io.github.lingqiqi5211.ezhooktool.core

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** 配置切换发布新状态；同步嵌套查询沿用外层状态，退出后不在线程上保留旧加载器。 */
internal class ReflectionContext(
    capacity: Int = 4096,
    maxResultSize: Int = 256,
) {
    @Volatile
    internal var current = ReflectionState(capacity = capacity, maxResultSize = maxResultSize)
        private set
    internal val active = ThreadLocal<ReflectionState>()

    val state: ReflectionState get() = active.get() ?: current

    @OptIn(ExperimentalContracts::class)
    inline fun <T> query(block: () -> T): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        if (active.get() != null) return block()
        active.set(current)
        return try {
            block()
        } finally {
            active.remove()
        }
    }

    @Synchronized
    fun update(change: (ReflectionState) -> ReflectionState) {
        val previous = current
        val next = change(previous).copy(version = previous.version + 1)
        current = next
        previous.retire()
    }
}

/** 缓存持有强引用，但总条目与单个结果规模有界；退休后清空且拒绝晚到的写入。 */
internal data class ReflectionState(
    val classLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
    val initialized: Boolean = false,
    val memberResolver: MemberResolver = DefaultMemberResolver,
    val classResolver: ClassResolver = DefaultClassResolver,
    val cacheEnabled: Boolean = true,
    val version: Long = 0,
    val capacity: Int = 4096,
    val maxResultSize: Int = 256,
) {
    private val cache = LinkedHashMap<CacheKey, Any>(16, 0.75f, true)
    private var retired = false
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L
    private var skippedOversizeResults = 0L

    fun get(
        owner: Any,
        bucket: ReflectCacheBucket?,
        key: Any,
    ): Any? =
        synchronized(cache) {
            if (!cacheEnabled || retired) {
                null
            } else {
                val value = cache[CacheKey(owner, bucket, key)]
                if (value == null) misses++ else hits++
                value
            }
        }

    fun put(
        owner: Any,
        bucket: ReflectCacheBucket?,
        key: Any,
        value: Any,
    ) {
        if (!cacheEnabled) return
        synchronized(cache) {
            if (retired) return
            if (value is Collection<*> && value.size > maxResultSize) {
                skippedOversizeResults++
                return
            }
            cache[CacheKey(owner, bucket, key)] = value
            if (cache.size > capacity) {
                val iterator = cache.entries.iterator()
                iterator.next()
                iterator.remove()
                evictions++
            }
        }
    }

    fun retire() =
        synchronized(cache) {
            retired = true
            cache.clear()
        }

    internal val size: Int get() = synchronized(cache) { cache.size }

    /** 当前状态的计数快照；只含数字，不引用键、结果或解析器。 */
    internal fun stats(): ReflectCacheStats =
        synchronized(cache) {
            ReflectCacheStats(
                generation = version,
                entries = cache.size,
                capacity = capacity,
                hits = hits,
                misses = misses,
                evictions = evictions,
                skippedOversizeResults = skippedOversizeResults,
            )
        }

    private class CacheKey(
        val owner: Any,
        val bucket: ReflectCacheBucket?,
        val query: Any,
    ) {
        override fun hashCode(): Int = 31 * (31 * System.identityHashCode(owner) + (bucket?.hashCode() ?: 0)) + query.hashCode()

        override fun equals(other: Any?): Boolean =
            other is CacheKey && owner === other.owner && bucket == other.bucket && query == other.query
    }
}

package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.EzReflect

/**
 * 反射查询的公共基础能力。
 *
 * 子类负责声明具体条件，基类负责严格查询和主动缓存 key。
 */
abstract class BaseQuery<T> internal constructor() {
    private var manualCacheKey: String? = null
    private var singleResult = false

    /** 为当前查询指定主动缓存 key。 */
    fun cacheKey(key: String) {
        manualCacheKey = key
    }

    /** 要求当前非批量查询只能命中一个结果。 */
    fun findSingle() {
        singleResult = true
    }

    internal val requiresSingleResult: Boolean
        get() = singleResult

    internal fun cacheKeyOrManual(autoKey: List<Any>?, cacheable: Boolean): List<Any>? =
        manualCacheKey?.let { listOf("manual", it) } ?: autoKey.takeIf { cacheable }
}

internal object QueryFilterContext {
    private const val TAG = "EzReflect"
    private val depth = ThreadLocal.withInitial { 0 }

    val insideFilter: Boolean
        get() = depth.get() > 0

    inline fun <T> run(block: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            block()
        } finally {
            depth.set(depth.get() - 1)
        }
    }

    fun warnNestedFind(apiName: String) {
        if (!insideFilter) return
        EzReflect.logger.warn(
            TAG,
            "Calling $apiName inside filter is discouraged. Prefer structured query conditions to avoid deep nested lookup.",
        )
    }
}

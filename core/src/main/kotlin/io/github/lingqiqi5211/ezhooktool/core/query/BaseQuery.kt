package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import java.lang.reflect.Type

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

/**
 * 按位比较实际参数类型与期望类型列表；期望列表中 `null` 表示该位置用 [VagueType] 占位，不参与比较。
 *
 * 数量必须完全一致；[VagueType] 只跳过精确匹配，不代表可变参数。
 */
internal fun parameterTypesMatchVague(actual: Array<Class<*>>, expected: List<Class<*>?>): Boolean {
    if (actual.size != expected.size) return false
    for (i in actual.indices) {
        val expectedType = expected[i] ?: continue
        if (actual[i] != expectedType) return false
    }
    return true
}

/** 按位用 [GenericTypeMatcher] 比较擦除前的 `Type` 数组；数量不一致直接判为不匹配。 */
internal fun matchesGenericTypes(actual: Array<Type>, matchers: List<GenericTypeMatcher>): Boolean {
    if (actual.size != matchers.size) return false
    for (i in actual.indices) {
        if (!matchers[i].matches(actual[i])) return false
    }
    return true
}

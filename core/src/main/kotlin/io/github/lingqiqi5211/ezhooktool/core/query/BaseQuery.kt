package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import java.lang.reflect.Type
import java.util.Collections

internal enum class QueryScope {
    DECLARED,
    FIRST_MATCHING_CLASS,
    HIERARCHY,
    CLASS_NAMES,
}

internal enum class QueryResultMode {
    FIRST,
    EXACTLY_ONE,
    ALL,
}

internal class QueryTerm<T>(
    val key: Any?,
    val description: String,
    val predicate: T.() -> Boolean,
)

/** 条件、搜索范围和结果模式的独立快照；用户谓词的闭包状态仍由调用者负责。 */
internal class QueryPlan<T>(
    queryType: Class<*>,
    terms: List<QueryTerm<T>>,
    nameTerms: List<QueryTerm<String>>,
    val scope: QueryScope,
    val resultMode: QueryResultMode,
    manualCacheKey: String?,
) {
    private val terms = terms.toList()
    private val nameTerms = nameTerms.toList()
    private val allTerms = this.nameTerms + this.terms

    val cacheKey: List<Any>? =
        when {
            manualCacheKey != null -> {
                listOf(queryType, scope, resultMode, "manual", manualCacheKey)
            }

            allTerms.any { it.key == null } -> {
                null
            }

            else -> {
                listOf(
                    queryType,
                    scope,
                    resultMode,
                    "auto",
                    Collections.unmodifiableList(this.nameTerms.map { it.key }),
                    Collections.unmodifiableList(this.terms.map { it.key }),
                )
            }
        }?.let { Collections.unmodifiableList(it) }

    val description: String? = allTerms.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.description }

    val findSuper: Boolean?
        get() =
            when (scope) {
                QueryScope.FIRST_MATCHING_CLASS -> null
                QueryScope.HIERARCHY -> true
                else -> false
            }

    val requiresSingleResult: Boolean
        get() = resultMode == QueryResultMode.EXACTLY_ONE

    val collectAll: Boolean
        get() = resultMode != QueryResultMode.FIRST

    val maxResults: Int?
        get() = if (requiresSingleResult) 2 else null

    fun matches(value: T): Boolean = terms.all { it.predicate(value) }

    fun matchesName(name: String): Boolean = nameTerms.all { it.predicate(name) }

    fun acceptsCount(count: Int): Boolean = if (requiresSingleResult) count == 1 else count > 0
}

/**
 * 反射查询的公共基础能力。
 *
 * 子类负责声明具体条件，基类负责严格查询和主动缓存 key。
 */
abstract class BaseQuery<T> internal constructor() {
    private val terms = mutableListOf<QueryTerm<T>>()
    private val nameTerms = mutableListOf<QueryTerm<String>>()
    private var manualCacheKey: String? = null
    private var singleResult = false
    internal var searchScope: QueryScope = QueryScope.DECLARED

    /** 为当前查询指定主动缓存 key。 */
    fun cacheKey(key: String) {
        manualCacheKey = key
    }

    /** 要求当前非批量查询只能命中一个结果。 */
    fun findSingle() {
        singleResult = true
    }

    internal fun addCondition(
        key: Any?,
        description: String,
        predicate: T.() -> Boolean,
    ) {
        terms += QueryTerm(key, description, predicate)
    }

    internal fun addNameCondition(
        key: Any,
        description: String,
        predicate: String.() -> Boolean,
    ) {
        nameTerms += QueryTerm(key, description, predicate)
    }

    internal fun freeze(resultMode: QueryResultMode): QueryPlan<T> =
        QueryPlan(
            queryType = javaClass,
            terms = terms,
            nameTerms = nameTerms,
            scope = searchScope,
            resultMode = if (resultMode == QueryResultMode.FIRST && singleResult) QueryResultMode.EXACTLY_ONE else resultMode,
            manualCacheKey = manualCacheKey,
        )
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
internal fun parameterTypesMatchVague(
    actual: Array<Class<*>>,
    expected: List<Class<*>?>,
): Boolean {
    if (actual.size != expected.size) return false
    for (i in actual.indices) {
        val expectedType = expected[i] ?: continue
        if (actual[i] != expectedType) return false
    }
    return true
}

/** 按位用 [GenericTypeMatcher] 比较擦除前的 `Type` 数组；数量不一致直接判为不匹配。 */
internal fun matchesGenericTypes(
    actual: Array<Type>,
    matchers: List<GenericTypeMatcher>,
): Boolean {
    if (actual.size != matchers.size) return false
    for (i in actual.indices) {
        if (!matchers[i].matches(actual[i])) return false
    }
    return true
}

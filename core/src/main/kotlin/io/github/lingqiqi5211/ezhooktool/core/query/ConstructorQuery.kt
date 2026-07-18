package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.ConstructorCondition
import io.github.lingqiqi5211.ezhooktool.core.canAcceptAll
import io.github.lingqiqi5211.ezhooktool.core.describeTypes
import io.github.lingqiqi5211.ezhooktool.core.isSynthetic
import io.github.lingqiqi5211.ezhooktool.core.paramCount
import io.github.lingqiqi5211.ezhooktool.core.toReadableTypeName
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.function.Predicate

private enum class ConstructorCachePart {
    PARAM_COUNT,
    PARAM_COUNT_RANGE,
    PARAMETER_TYPES,
    ASSIGNABLE_PARAMETER_TYPES,
    VAGUE_PARAMETER_TYPES,
    EXCEPTION_TYPES,
    FLAGS,
}

private data class ConstructorIntRangeKey(val start: Int, val end: Int)

private val constructorCachePartOrder = listOf(
    ConstructorCachePart.PARAM_COUNT,
    ConstructorCachePart.PARAM_COUNT_RANGE,
    ConstructorCachePart.PARAMETER_TYPES,
    ConstructorCachePart.ASSIGNABLE_PARAMETER_TYPES,
    ConstructorCachePart.VAGUE_PARAMETER_TYPES,
    ConstructorCachePart.EXCEPTION_TYPES,
    ConstructorCachePart.FLAGS,
)

private fun constructorCacheKeyOf(parts: Map<ConstructorCachePart, Any>): List<Any> {
    val result = ArrayList<Any>(parts.size * 2)
    for (part in constructorCachePartOrder) {
        val value = parts[part] ?: continue
        result += part
        result += value
    }
    return result
}

/**
 * 构造器查询条件。
 *
 * 用在 `findConstructor`、`findConstructorOrNull`、`findAllConstructors` 的查询块里。
 * 多个条件会同时生效，全部满足才算匹配。
 *
 * ```kotlin
 * val constructor = clazz.findConstructor {
 *     paramCount(2)
 *     params(String::class.java, Int::class.java)
 * }
 * ```
 */
class ConstructorQuery internal constructor() : BaseQuery<Constructor<*>>() {
    private val conditions = mutableListOf<ConstructorCondition>()
    private val cacheParts = mutableMapOf<ConstructorCachePart, Any>()
    private val descriptions = mutableListOf<String>()
    private val flags = mutableMapOf<String, Boolean>()
    private var cacheable = true

    /** 限定参数数量。 */
    fun paramCount(value: Int) {
        conditions += { paramCount == value }
        cacheParts[ConstructorCachePart.PARAM_COUNT] = value
        descriptions += "paramCount=$value"
    }

    /** 限定参数数量范围。 */
    fun paramCountIn(range: IntRange) {
        conditions += { paramCount in range }
        cacheParts[ConstructorCachePart.PARAM_COUNT_RANGE] = ConstructorIntRangeKey(range.first, range.last)
        descriptions += "paramCount=${range.first}..${range.last}"
    }

    /** 限定为无参数构造器。 */
    fun noParams() {
        paramCount(0)
    }

    /** 限定为有参数构造器。 */
    fun hasParams() {
        conditions += { paramCount > 0 }
        cacheParts[ConstructorCachePart.PARAM_COUNT_RANGE] = ConstructorIntRangeKey(1, Int.MAX_VALUE)
        descriptions += "paramCount>=1"
    }

    /**
     * 限定完整参数类型。
     *
     * 参数数量和顺序都必须一致，且类型必须 **完全相等**：
     * `Int::class.java`（即 `int.class`）与 `Integer.class` 视为不同类型。
     * 如果需要让 primitive 与 wrapper 互相匹配（或允许子类）请改用 [parameterTypesAssignableFrom]。
     */
    fun parameterTypes(vararg types: Class<*>) {
        conditions += { parameterTypes.contentEquals(types) }
        cacheParts[ConstructorCachePart.PARAMETER_TYPES] = types.toList()
        descriptions += "params=${types.describeTypes()}"
    }

    /** [parameterTypes] 的短名称。 */
    fun params(vararg types: Class<*>) {
        parameterTypes(*types)
    }

    /**
     * 限定构造器参数能接收指定类型。
     *
     * 例如构造器参数是 `CharSequence`，传入 `String::class.java` 时会匹配。
     */
    fun parameterTypesAssignableFrom(vararg types: Class<*>) {
        conditions += { parameterTypes.canAcceptAll(types) }
        cacheParts[ConstructorCachePart.ASSIGNABLE_PARAMETER_TYPES] = types.toList()
        descriptions += "paramsAssignableFrom=${types.describeTypes()}"
    }

    /** [parameterTypesAssignableFrom] 的短名称。 */
    fun paramsAssignableFrom(vararg types: Class<*>) {
        parameterTypesAssignableFrom(*types)
    }

    /**
     * 限定参数类型，允许其中某些位置用 [VagueType] 占位跳过精确匹配。
     *
     * 参数数量仍必须与 [types] 长度一致；非 [VagueType] 的位置按 [parameterTypes] 语义要求完全相等。
     */
    fun parameterTypesVague(vararg types: Any) {
        val expected = types.map { if (it === VagueType) null else it as Class<*> }
        conditions += { parameterTypesMatchVague(parameterTypes, expected) }
        cacheParts[ConstructorCachePart.VAGUE_PARAMETER_TYPES] = expected
        val described = types.joinToString(", ") { if (it === VagueType) "*" else (it as Class<*>).toReadableTypeName() }
        descriptions += "paramsVague=[$described]"
    }

    /**
     * 限定形参在 [Constructor.getGenericParameterTypes] 层面的类型，按 [GenericTypeMatcher] 逐位匹配。
     *
     * 与 [parameterTypes] 不同，这里使用擦除前的 `Type`，可以匹配类型变量或参数化类型的 raw type。
     * 此条件禁用查询缓存。
     */
    fun genericParameterTypes(vararg matchers: GenericTypeMatcher) {
        val snapshot = matchers.toList()
        conditions += { matchesGenericTypes(genericParameterTypes, snapshot) }
        cacheable = false
        descriptions += "genericParams=[${snapshot.joinToString(", ")}]"
    }

    /** 限定声明的异常类型。 */
    fun exceptionTypes(vararg types: Class<*>) {
        conditions += { exceptionTypes.contentEquals(types) }
        cacheParts[ConstructorCachePart.EXCEPTION_TYPES] = types.toList()
        descriptions += "exceptions=${types.describeTypes()}"
    }

    /** 限定为 public 构造器。 */
    fun isPublic() {
        flag("public", true) { Modifier.isPublic(modifiers) }
    }

    /** 限定为非 public 构造器。 */
    fun notPublic() {
        flag("public", false) { Modifier.isPublic(modifiers) }
    }

    /** 限定为 private 构造器。 */
    fun isPrivate() {
        flag("private", true) { Modifier.isPrivate(modifiers) }
    }

    /** 限定为非 private 构造器。 */
    fun notPrivate() {
        flag("private", false) { Modifier.isPrivate(modifiers) }
    }

    /** 限定为 protected 构造器。 */
    fun isProtected() {
        flag("protected", true) { Modifier.isProtected(modifiers) }
    }

    /** 限定为非 protected 构造器。 */
    fun notProtected() {
        flag("protected", false) { Modifier.isProtected(modifiers) }
    }

    /** 限定为可变参数构造器。 */
    fun isVarArgs() {
        flag("varargs", true) { this.isVarArgs }
    }

    /** 限定为非可变参数构造器。 */
    fun notVarArgs() {
        flag("varargs", false) { this.isVarArgs }
    }

    /** 限定为 synthetic 构造器。 */
    fun isSynthetic() {
        flag("synthetic", true) { isSynthetic }
    }

    /** 限定为非 synthetic 构造器。 */
    fun notSynthetic() {
        flag("synthetic", false) { isSynthetic }
    }

    /** 添加自定义 Kotlin 条件。 */
    fun filter(condition: ConstructorCondition) {
        conditions += { QueryFilterContext.run { condition(this) } }
        cacheable = false
        descriptions += "customFilter"
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Constructor<*>>) {
        conditions += { predicate.test(this) }
        cacheable = false
        descriptions += "customFilter"
    }

    private fun flag(name: String, value: Boolean, condition: Constructor<*>.() -> Boolean) {
        conditions += { condition(this) == value }
        flags[name] = value
        cacheParts[ConstructorCachePart.FLAGS] = flags.toSortedMap().toList()
        descriptions += "$name=$value"
    }

    internal fun cacheKeyOrNull(): List<Any>? =
        cacheKeyOrManual(constructorCacheKeyOf(cacheParts), cacheable)

    internal fun describe(): String? =
        descriptions.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")

    internal fun matches(constructor: Constructor<*>): Boolean =
        conditions.all { it(constructor) }
}

internal fun constructorExactCacheKeys(constructor: Constructor<*>): List<List<Any>> {
    val parameterTypes = constructor.parameterTypes.toList()
    val base = mapOf(ConstructorCachePart.PARAMETER_TYPES to parameterTypes)
    val withParamCount = base + (ConstructorCachePart.PARAM_COUNT to parameterTypes.size)

    return listOf(
        constructorCacheKeyOf(base),
        constructorCacheKeyOf(withParamCount),
    )
}

internal fun constructorQuery(block: ConstructorQuery.() -> Unit): ConstructorQuery =
    ConstructorQuery().apply(block)

internal fun constructorCondition(query: ConstructorQuery): ConstructorCondition =
    { query.matches(this) }

internal fun constructorCondition(block: ConstructorQuery.() -> Unit): ConstructorCondition =
    constructorCondition(constructorQuery(block))

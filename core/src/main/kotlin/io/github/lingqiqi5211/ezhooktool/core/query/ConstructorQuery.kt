package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.ConstructorCondition
import io.github.lingqiqi5211.ezhooktool.core.isTypeMatch
import io.github.lingqiqi5211.ezhooktool.core.paramCount
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.function.Predicate

private enum class ConstructorCachePart {
    PARAM_COUNT,
    PARAM_COUNT_RANGE,
    PARAMETER_TYPES,
    ASSIGNABLE_PARAMETER_TYPES,
    EXCEPTION_TYPES,
    FLAGS,
}

private data class ConstructorIntRangeKey(val start: Int, val end: Int)

private val constructorCachePartOrder = listOf(
    ConstructorCachePart.PARAM_COUNT,
    ConstructorCachePart.PARAM_COUNT_RANGE,
    ConstructorCachePart.PARAMETER_TYPES,
    ConstructorCachePart.ASSIGNABLE_PARAMETER_TYPES,
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

private fun Array<Class<*>>.canAcceptAll(types: Array<out Class<*>>): Boolean {
    if (size != types.size) return false
    for (index in indices) {
        if (!isTypeMatch(types[index], this[index])) return false
    }
    return true
}

private fun Constructor<*>.isSyntheticConstructor(): Boolean = modifiers and 0x00001000 != 0

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
class ConstructorQuery internal constructor() {
    private val conditions = mutableListOf<ConstructorCondition>()
    private val cacheParts = mutableMapOf<ConstructorCachePart, Any>()
    private val flags = mutableMapOf<String, Boolean>()
    private var cacheable = true

    /** 限定参数数量。 */
    fun paramCount(value: Int) {
        conditions += { paramCount == value }
        cacheParts[ConstructorCachePart.PARAM_COUNT] = value
    }

    /** 限定参数数量范围。 */
    fun paramCountIn(range: IntRange) {
        conditions += { paramCount in range }
        cacheParts[ConstructorCachePart.PARAM_COUNT_RANGE] = ConstructorIntRangeKey(range.first, range.last)
    }

    /** 限定为无参数构造器。 */
    fun noParams() {
        paramCount(0)
    }

    /** 限定为有参数构造器。 */
    fun hasParams() {
        paramCountIn(1..Int.MAX_VALUE)
    }

    /**
     * 限定完整参数类型。
     *
     * 参数数量和顺序都必须一致。
     */
    fun parameterTypes(vararg types: Class<*>) {
        conditions += { parameterTypes.contentEquals(types) }
        cacheParts[ConstructorCachePart.PARAMETER_TYPES] = types.toList()
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
    }

    /** [parameterTypesAssignableFrom] 的短名称。 */
    fun paramsAssignableFrom(vararg types: Class<*>) {
        parameterTypesAssignableFrom(*types)
    }

    /** 限定声明的异常类型。 */
    fun exceptionTypes(vararg types: Class<*>) {
        conditions += { exceptionTypes.contentEquals(types) }
        cacheParts[ConstructorCachePart.EXCEPTION_TYPES] = types.toList()
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
        flag("synthetic", true) { isSyntheticConstructor() }
    }

    /** 限定为非 synthetic 构造器。 */
    fun notSynthetic() {
        flag("synthetic", false) { isSyntheticConstructor() }
    }

    /** 添加自定义 Kotlin 条件。 */
    fun filter(condition: ConstructorCondition) {
        conditions += condition
        cacheable = false
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Constructor<*>>) {
        conditions += { predicate.test(this) }
        cacheable = false
    }

    private fun flag(name: String, value: Boolean, condition: Constructor<*>.() -> Boolean) {
        conditions += { condition(this) == value }
        flags[name] = value
        cacheParts[ConstructorCachePart.FLAGS] = flags.toSortedMap().toList()
    }

    internal fun cacheKeyOrNull(): List<Any>? =
        if (cacheable) constructorCacheKeyOf(cacheParts) else null

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

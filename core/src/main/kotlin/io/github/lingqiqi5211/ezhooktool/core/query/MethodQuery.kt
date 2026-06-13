package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.MethodCondition
import io.github.lingqiqi5211.ezhooktool.core.isTypeMatch
import io.github.lingqiqi5211.ezhooktool.core.paramCount
import io.github.lingqiqi5211.ezhooktool.core.toReadableTypeName
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Predicate

private enum class MethodCachePart {
    NAME,
    NAME_CONTAINS,
    NAME_STARTS_WITH,
    NAME_ENDS_WITH,
    PARAM_COUNT,
    PARAM_COUNT_RANGE,
    RETURN_TYPE,
    RETURN_TYPE_EXTENDS_FROM,
    PARAMETER_TYPES,
    ASSIGNABLE_PARAMETER_TYPES,
    EXCEPTION_TYPES,
    FLAGS,
}

private data class MethodTextMatchKey(val value: String, val ignoreCase: Boolean)

private data class MethodIntRangeKey(val start: Int, val end: Int)

private val methodCachePartOrder = listOf(
    MethodCachePart.NAME,
    MethodCachePart.NAME_CONTAINS,
    MethodCachePart.NAME_STARTS_WITH,
    MethodCachePart.NAME_ENDS_WITH,
    MethodCachePart.PARAM_COUNT,
    MethodCachePart.PARAM_COUNT_RANGE,
    MethodCachePart.RETURN_TYPE,
    MethodCachePart.RETURN_TYPE_EXTENDS_FROM,
    MethodCachePart.PARAMETER_TYPES,
    MethodCachePart.ASSIGNABLE_PARAMETER_TYPES,
    MethodCachePart.EXCEPTION_TYPES,
    MethodCachePart.FLAGS,
)

private fun methodCacheKeyOf(parts: Map<MethodCachePart, Any>): List<Any> {
    val result = ArrayList<Any>(parts.size * 2)
    for (part in methodCachePartOrder) {
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

private fun Method.isBridgeMethod(): Boolean = modifiers and 0x00000040 != 0

private fun Method.isSyntheticMethod(): Boolean = modifiers and 0x00001000 != 0

private fun Method.isDefaultMethod(): Boolean =
    declaringClass.isInterface &&
            Modifier.isPublic(modifiers) &&
            !Modifier.isAbstract(modifiers) &&
            !Modifier.isStatic(modifiers)

private fun Array<out Class<*>>.describeTypes(): String =
    joinToString(prefix = "[", postfix = "]") { it.toReadableTypeName() }

/**
 * 方法查询条件。
 *
 * 用在 `findMethod`、`findMethodOrNull`、`findAllMethods` 的查询块里。
 * 多个条件会同时生效，全部满足才算匹配。
 *
 * ```kotlin
 * val method = clazz.findMethod {
 *     name("foo")
 *     paramCount(2)
 *     returnType(String::class.java)
 * }
 * ```
 */
class MethodQuery internal constructor() : BaseQuery<Method>() {
    private val conditions = mutableListOf<MethodCondition>()
    private val cacheParts = mutableMapOf<MethodCachePart, Any>()
    private val descriptions = mutableListOf<String>()
    private val flags = mutableMapOf<String, Boolean>()
    private var cacheable = true
    private var searchSuperSet = false
    private var searchSuperValue: Boolean? = null

    /** 限定方法名。 */
    fun name(value: String) {
        conditions += { name == value }
        cacheParts[MethodCachePart.NAME] = value
        descriptions += "name=$value"
    }

    /** 限定方法名包含指定文本。 */
    fun nameContains(value: String, ignoreCase: Boolean = false) {
        conditions += { name.contains(value, ignoreCase) }
        cacheParts[MethodCachePart.NAME_CONTAINS] = MethodTextMatchKey(value, ignoreCase)
        descriptions += "name contains \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定方法名以指定文本开头。 */
    fun nameStartsWith(value: String, ignoreCase: Boolean = false) {
        conditions += { name.startsWith(value, ignoreCase) }
        cacheParts[MethodCachePart.NAME_STARTS_WITH] = MethodTextMatchKey(value, ignoreCase)
        descriptions += "name startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定方法名以指定文本结尾。 */
    fun nameEndsWith(value: String, ignoreCase: Boolean = false) {
        conditions += { name.endsWith(value, ignoreCase) }
        cacheParts[MethodCachePart.NAME_ENDS_WITH] = MethodTextMatchKey(value, ignoreCase)
        descriptions += "name endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定参数数量。 */
    fun paramCount(value: Int) {
        conditions += { paramCount == value }
        cacheParts[MethodCachePart.PARAM_COUNT] = value
        descriptions += "paramCount=$value"
    }

    /** 限定参数数量范围。 */
    fun paramCountIn(range: IntRange) {
        conditions += { paramCount in range }
        cacheParts[MethodCachePart.PARAM_COUNT_RANGE] = MethodIntRangeKey(range.first, range.last)
        descriptions += "paramCount=${range.first}..${range.last}"
    }

    /** 限定为无参数方法。 */
    fun noParams() {
        paramCount(0)
    }

    /** 限定为有参数方法。 */
    fun hasParams() {
        conditions += { paramCount > 0 }
        cacheParts[MethodCachePart.PARAM_COUNT_RANGE] = MethodIntRangeKey(1, Int.MAX_VALUE)
        descriptions += "paramCount>=1"
    }

    /** 限定返回值类型。 */
    fun returnType(value: Class<*>) {
        conditions += { returnType == value }
        cacheParts[MethodCachePart.RETURN_TYPE] = value
        descriptions += "returnType=${value.toReadableTypeName()}"
    }

    /** 限定返回值类型是 [value] 本身或子类。 */
    fun returnTypeExtendsFrom(value: Class<*>) {
        conditions += { isTypeMatch(returnType, value) }
        cacheParts[MethodCachePart.RETURN_TYPE_EXTENDS_FROM] = value
        descriptions += "returnType extends ${value.toReadableTypeName()}"
    }

    /** 限定返回值为 void。 */
    fun voidReturnType() {
        returnType(Void.TYPE)
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
        cacheParts[MethodCachePart.PARAMETER_TYPES] = types.toList()
        descriptions += "params=${types.describeTypes()}"
    }

    /** [parameterTypes] 的短名称。 */
    fun params(vararg types: Class<*>) {
        parameterTypes(*types)
    }

    /**
     * 限定方法参数能接收指定类型。
     *
     * 例如方法参数是 `CharSequence`，传入 `String::class.java` 时会匹配。
     */
    fun parameterTypesAssignableFrom(vararg types: Class<*>) {
        conditions += { parameterTypes.canAcceptAll(types) }
        cacheParts[MethodCachePart.ASSIGNABLE_PARAMETER_TYPES] = types.toList()
        descriptions += "paramsAssignableFrom=${types.describeTypes()}"
    }

    /** [parameterTypesAssignableFrom] 的短名称。 */
    fun paramsAssignableFrom(vararg types: Class<*>) {
        parameterTypesAssignableFrom(*types)
    }

    /** 限定声明的异常类型。 */
    fun exceptionTypes(vararg types: Class<*>) {
        conditions += { exceptionTypes.contentEquals(types) }
        cacheParts[MethodCachePart.EXCEPTION_TYPES] = types.toList()
        descriptions += "exceptions=${types.describeTypes()}"
    }

    /** 限定为 static 方法。 */
    fun isStatic() {
        isStatic(true)
    }

    /** 限定是否为 static 方法。 */
    fun isStatic(value: Boolean) {
        flag("static", value) { Modifier.isStatic(modifiers) }
    }

    /** 限定为非 static 方法。 */
    fun notStatic() {
        isStatic(false)
    }

    /** 限定为 public 方法。 */
    fun isPublic() {
        flag("public", true) { Modifier.isPublic(modifiers) }
    }

    /** 限定为非 public 方法。 */
    fun notPublic() {
        flag("public", false) { Modifier.isPublic(modifiers) }
    }

    /** 限定为 private 方法。 */
    fun isPrivate() {
        flag("private", true) { Modifier.isPrivate(modifiers) }
    }

    /** 限定为非 private 方法。 */
    fun notPrivate() {
        flag("private", false) { Modifier.isPrivate(modifiers) }
    }

    /** 限定为 protected 方法。 */
    fun isProtected() {
        flag("protected", true) { Modifier.isProtected(modifiers) }
    }

    /** 限定为非 protected 方法。 */
    fun notProtected() {
        flag("protected", false) { Modifier.isProtected(modifiers) }
    }

    /** 限定为 final 方法。 */
    fun isFinal() {
        flag("final", true) { Modifier.isFinal(modifiers) }
    }

    /** 限定为非 final 方法。 */
    fun notFinal() {
        flag("final", false) { Modifier.isFinal(modifiers) }
    }

    /** 限定为 abstract 方法。 */
    fun isAbstract() {
        flag("abstract", true) { Modifier.isAbstract(modifiers) }
    }

    /** 限定为非 abstract 方法。 */
    fun notAbstract() {
        flag("abstract", false) { Modifier.isAbstract(modifiers) }
    }

    /** 限定为 native 方法。 */
    fun isNative() {
        flag("native", true) { Modifier.isNative(modifiers) }
    }

    /** 限定为非 native 方法。 */
    fun notNative() {
        flag("native", false) { Modifier.isNative(modifiers) }
    }

    /** 限定为 synchronized 方法。 */
    fun isSynchronized() {
        flag("synchronized", true) { Modifier.isSynchronized(modifiers) }
    }

    /** 限定为非 synchronized 方法。 */
    fun notSynchronized() {
        flag("synchronized", false) { Modifier.isSynchronized(modifiers) }
    }

    /** 限定为可变参数方法。 */
    fun isVarArgs() {
        flag("varargs", true) { this.isVarArgs }
    }

    /** 限定为非可变参数方法。 */
    fun notVarArgs() {
        flag("varargs", false) { this.isVarArgs }
    }

    /** 限定为 synthetic 方法。 */
    fun isSynthetic() {
        flag("synthetic", true) { isSyntheticMethod() }
    }

    /** 限定为非 synthetic 方法。 */
    fun notSynthetic() {
        flag("synthetic", false) { isSyntheticMethod() }
    }

    /** 限定为 bridge 方法。 */
    fun isBridge() {
        flag("bridge", true) { isBridgeMethod() }
    }

    /** 限定为非 bridge 方法。 */
    fun notBridge() {
        flag("bridge", false) { isBridgeMethod() }
    }

    /** 限定为 interface default 方法。 */
    fun isDefault() {
        flag("default", true) { isDefaultMethod() }
    }

    /** 限定为非 interface default 方法。 */
    fun notDefault() {
        flag("default", false) { isDefaultMethod() }
    }

    /**
     * 只在当前类中查找。
     */
    fun findOnlyClass() {
        searchSuperSet = true
        searchSuperValue = false
    }

    /**
     * 查找当前类和全部父类。
     */
    fun findAndSuper() {
        searchSuperSet = true
        searchSuperValue = true
    }

    /** [findOnlyClass] 的旧名称。 */
    @Deprecated(
        message = "currentClassOnly is an old name. The last recommended version for this name is 1.0.4. Use findOnlyClass instead.",
        replaceWith = ReplaceWith("findOnlyClass()"),
    )
    fun currentClassOnly() {
        findOnlyClass()
    }

    /** [findAndSuper] 的旧名称。 */
    @Deprecated(
        message = "includeSuper is an old name. The last recommended version for this name is 1.0.4. Use findAndSuper instead.",
        replaceWith = ReplaceWith("findAndSuper()"),
    )
    fun includeSuper() {
        findAndSuper()
    }

    /** 添加自定义 Kotlin 条件。 */
    fun filter(condition: MethodCondition) {
        conditions += { QueryFilterContext.run { condition(this) } }
        cacheable = false
        descriptions += "customFilter"
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Method>) {
        conditions += { predicate.test(this) }
        cacheable = false
        descriptions += "customFilter"
    }

    private fun flag(name: String, value: Boolean, condition: Method.() -> Boolean) {
        conditions += { condition(this) == value }
        flags[name] = value
        cacheParts[MethodCachePart.FLAGS] = flags.toSortedMap().toList()
        descriptions += "$name=$value"
    }

    internal fun effectiveFindSuper(defaultValue: Boolean?): Boolean? =
        if (searchSuperSet) searchSuperValue else defaultValue

    internal fun cacheKeyOrNull(): List<Any>? =
        cacheKeyOrManual(methodCacheKeyOf(cacheParts), cacheable)

    internal fun describe(): String? =
        descriptions.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")

    internal fun matches(method: Method): Boolean = conditions.all { it(method) }
}

internal fun methodExactCacheKeys(method: Method): List<List<Any>> {
    val parameterTypes = method.parameterTypes.toList()
    val paramCount = parameterTypes.size
    val exact = mapOf(
        MethodCachePart.NAME to method.name,
        MethodCachePart.RETURN_TYPE to method.returnType,
        MethodCachePart.PARAMETER_TYPES to parameterTypes,
    )
    val exactWithParamCount = exact + (MethodCachePart.PARAM_COUNT to paramCount)

    return listOf(
        methodCacheKeyOf(exact),
        methodCacheKeyOf(exactWithParamCount),
    )
}

internal fun methodQuery(block: MethodQuery.() -> Unit): MethodQuery =
    MethodQuery().apply(block)

internal fun methodCondition(query: MethodQuery): MethodCondition =
    { query.matches(this) }

internal fun methodCondition(block: MethodQuery.() -> Unit): MethodCondition =
    methodCondition(methodQuery(block))

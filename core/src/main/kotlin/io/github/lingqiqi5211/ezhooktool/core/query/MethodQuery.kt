package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.MethodCondition
import io.github.lingqiqi5211.ezhooktool.core.canAcceptAll
import io.github.lingqiqi5211.ezhooktool.core.describeTypes
import io.github.lingqiqi5211.ezhooktool.core.isBridge
import io.github.lingqiqi5211.ezhooktool.core.isSynthetic
import io.github.lingqiqi5211.ezhooktool.core.isTypeMatch
import io.github.lingqiqi5211.ezhooktool.core.paramCount
import io.github.lingqiqi5211.ezhooktool.core.toReadableTypeName
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
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
    VAGUE_PARAMETER_TYPES,
    EXCEPTION_TYPES,
    FLAGS,
}

private fun Method.isDefaultMethod(): Boolean =
    declaringClass.isInterface &&
        Modifier.isPublic(modifiers) &&
        !Modifier.isAbstract(modifiers) &&
        !Modifier.isStatic(modifiers)

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
    init {
        searchScope = QueryScope.FIRST_MATCHING_CLASS
    }

    /** 限定方法名。 */
    fun name(value: String) {
        addCondition(MethodCachePart.NAME to value, "name=$value") { name == value }
    }

    /** 限定方法名包含指定文本。 */
    fun nameContains(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addCondition(
            MethodCachePart.NAME_CONTAINS to (value to ignoreCase),
            "name contains \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { name.contains(value, ignoreCase) }
    }

    /** 限定方法名以指定文本开头。 */
    fun nameStartsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addCondition(
            MethodCachePart.NAME_STARTS_WITH to (value to ignoreCase),
            "name startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { name.startsWith(value, ignoreCase) }
    }

    /** 限定方法名以指定文本结尾。 */
    fun nameEndsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addCondition(
            MethodCachePart.NAME_ENDS_WITH to (value to ignoreCase),
            "name endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { name.endsWith(value, ignoreCase) }
    }

    /** 限定参数数量。 */
    fun paramCount(value: Int) {
        addCondition(MethodCachePart.PARAM_COUNT to value, "paramCount=$value") { paramCount == value }
    }

    /** 限定参数数量范围。 */
    fun paramCountIn(range: IntRange) {
        addCondition(MethodCachePart.PARAM_COUNT_RANGE to range, "paramCount=${range.first}..${range.last}") { paramCount in range }
    }

    /** 限定为无参数方法。 */
    fun noParams() {
        paramCount(0)
    }

    /** 限定为有参数方法。 */
    fun hasParams() {
        addCondition(MethodCachePart.PARAM_COUNT_RANGE to (1..Int.MAX_VALUE), "paramCount>=1") { paramCount > 0 }
    }

    /** 限定返回值类型。 */
    fun returnType(value: Class<*>) {
        addCondition(MethodCachePart.RETURN_TYPE to value, "returnType=${value.toReadableTypeName()}") { returnType == value }
    }

    /** 限定返回值类型是 [value] 本身或子类。 */
    fun returnTypeExtendsFrom(value: Class<*>) {
        addCondition(MethodCachePart.RETURN_TYPE_EXTENDS_FROM to value, "returnType extends ${value.toReadableTypeName()}") {
            isTypeMatch(returnType, value)
        }
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
        val snapshot = types.copyOf()
        addCondition(
            MethodCachePart.PARAMETER_TYPES to Collections.unmodifiableList(snapshot.toList()),
            "params=${snapshot.describeTypes()}",
        ) {
            parameterTypes.contentEquals(snapshot)
        }
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
        val snapshot = types.copyOf()
        addCondition(
            MethodCachePart.ASSIGNABLE_PARAMETER_TYPES to Collections.unmodifiableList(snapshot.toList()),
            "paramsAssignableFrom=${snapshot.describeTypes()}",
        ) { parameterTypes.canAcceptAll(snapshot) }
    }

    /** [parameterTypesAssignableFrom] 的短名称。 */
    fun paramsAssignableFrom(vararg types: Class<*>) {
        parameterTypesAssignableFrom(*types)
    }

    /**
     * 限定参数类型，允许其中某些位置用 [VagueType] 占位跳过精确匹配。
     *
     * 参数数量仍必须与 [types] 长度一致；非 [VagueType] 的位置按 [parameterTypes] 语义要求完全相等。
     * 常用于只关心部分参数类型、其余参数类型随版本变化的场景。
     *
     * ```kotlin
     * clazz.findMethod {
     *     name("bind")
     *     parameterTypesVague(String::class.java, VagueType, Boolean::class.javaObjectType)
     * }
     * ```
     */
    fun parameterTypesVague(vararg types: Any) {
        val expected = Collections.unmodifiableList(types.map { if (it === VagueType) null else it as Class<*> })
        val described = expected.joinToString(", ") { it?.toReadableTypeName() ?: "*" }
        addCondition(MethodCachePart.VAGUE_PARAMETER_TYPES to expected, "paramsVague=[$described]") {
            parameterTypesMatchVague(parameterTypes, expected)
        }
    }

    /**
     * 限定形参在 [Method.getGenericParameterTypes] 层面的类型，按 [GenericTypeMatcher] 逐位匹配。
     *
     * 与 [parameterTypes] 不同，这里使用擦除前的 `Type`：可以匹配 [GenericTypeMatcher.typeVariableNamed]
     * 声明的类型变量，或 [GenericTypeMatcher.rawType] 匹配的参数化类型；桥接方法（bridge method）在此处
     * 已被擦除为具体 `Class`，不会命中类型变量条件。此条件禁用查询缓存。
     *
     * @param matchers 按参数位置提供的匹配器；数量必须与目标方法的参数数量一致才能命中
     */
    fun genericParameterTypes(vararg matchers: GenericTypeMatcher) {
        val snapshot = matchers.toList()
        addCondition(null, "genericParams=[${snapshot.joinToString(", ")}]") { matchesGenericTypes(genericParameterTypes, snapshot) }
    }

    /**
     * 限定 [Method.getGenericReturnType]，用于区分擦除前的泛型返回类型（例如声明为 `T` 的方法）。
     * 此条件禁用查询缓存。
     */
    fun genericReturnType(matcher: GenericTypeMatcher) {
        addCondition(null, "genericReturnType=$matcher") { matcher.matches(genericReturnType) }
    }

    /** 限定声明的异常类型。 */
    fun exceptionTypes(vararg types: Class<*>) {
        val snapshot = types.copyOf()
        addCondition(
            MethodCachePart.EXCEPTION_TYPES to Collections.unmodifiableList(snapshot.toList()),
            "exceptions=${snapshot.describeTypes()}",
        ) {
            exceptionTypes.contentEquals(snapshot)
        }
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
        flag("synthetic", true) { isSynthetic }
    }

    /** 限定为非 synthetic 方法。 */
    fun notSynthetic() {
        flag("synthetic", false) { isSynthetic }
    }

    /** 限定为 bridge 方法。 */
    fun isBridge() {
        flag("bridge", true) { isBridge }
    }

    /** 限定为非 bridge 方法。 */
    fun notBridge() {
        flag("bridge", false) { isBridge }
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
        searchScope = QueryScope.DECLARED
    }

    /**
     * 查找当前类和全部父类。
     */
    fun findAndSuper() {
        searchScope = QueryScope.HIERARCHY
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
        addCondition(null, "customFilter") { QueryFilterContext.run { condition(this) } }
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Method>) {
        filter { predicate.test(this) }
    }

    private fun flag(
        name: String,
        value: Boolean,
        condition: Method.() -> Boolean,
    ) {
        addCondition(MethodCachePart.FLAGS to (name to value), "$name=$value") { condition(this) == value }
    }
}

internal fun methodQuery(block: MethodQuery.() -> Unit): MethodQuery = MethodQuery().apply(block)

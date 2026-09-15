package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.findConstructorsMatching
import io.github.lingqiqi5211.ezhooktool.core.findFieldsMatching
import io.github.lingqiqi5211.ezhooktool.core.findMethodsMatching
import java.util.function.Predicate

private enum class ClassCachePart {
    NAME,
    NAME_CONTAINS,
    NAME_STARTS_WITH,
    NAME_ENDS_WITH,
    PACKAGE_NAME,
    PACKAGE_CONTAINS,
    PACKAGE_STARTS_WITH,
    PACKAGE_ENDS_WITH,
    SIMPLE_NAME,
    SIMPLE_NAME_CONTAINS,
    SIMPLE_NAME_STARTS_WITH,
    SIMPLE_NAME_ENDS_WITH,
    SUBCLASS_OF,
    HAS_METHOD,
    HAS_FIELD,
    HAS_CONSTRUCTOR,
}

private fun String.packagePart(): String = substringBeforeLast('.', missingDelimiterValue = "")

private fun String.simpleClassName(): String = substringAfterLast('.').substringAfterLast('$')

/**
 * 类查询条件。
 *
 * 类名条件会先执行，只有命中后才会加载 [Class] 并执行成员反推等较重条件。
 */
class ClassQuery internal constructor() : BaseQuery<Class<*>>() {
    init {
        searchScope = QueryScope.CLASS_NAMES
    }

    /** 限定完整类名。 */
    fun name(value: String) {
        addNameCondition(ClassCachePart.NAME to value, "name=$value") { this == value }
    }

    /** 限定完整类名包含指定文本。 */
    fun nameContains(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.NAME_CONTAINS to (value to ignoreCase),
            "name contains \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { contains(value, ignoreCase) }
    }

    /** 限定完整类名以指定文本开头。 */
    fun nameStartsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.NAME_STARTS_WITH to (value to ignoreCase),
            "name startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { startsWith(value, ignoreCase) }
    }

    /** 限定完整类名以指定文本结尾。 */
    fun nameEndsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.NAME_ENDS_WITH to (value to ignoreCase),
            "name endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { endsWith(value, ignoreCase) }
    }

    /** 限定包名。 */
    fun packageName(value: String) {
        addNameCondition(ClassCachePart.PACKAGE_NAME to value, "package=$value") { packagePart() == value }
    }

    /** 限定包名包含指定文本。 */
    fun packageContains(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.PACKAGE_CONTAINS to (value to ignoreCase),
            "package contains \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { packagePart().contains(value, ignoreCase) }
    }

    /** 限定包名以指定文本开头。 */
    fun packageStartsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.PACKAGE_STARTS_WITH to (value to ignoreCase),
            "package startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { packagePart().startsWith(value, ignoreCase) }
    }

    /** 限定包名以指定文本结尾。 */
    fun packageEndsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.PACKAGE_ENDS_WITH to (value to ignoreCase),
            "package endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { packagePart().endsWith(value, ignoreCase) }
    }

    /** 限定简单类名。 */
    fun simpleName(value: String) {
        addNameCondition(ClassCachePart.SIMPLE_NAME to value, "simpleName=$value") { simpleClassName() == value }
    }

    /** 限定简单类名包含指定文本。 */
    fun simpleNameContains(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.SIMPLE_NAME_CONTAINS to (value to ignoreCase),
            "simpleName contains \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { simpleClassName().contains(value, ignoreCase) }
    }

    /** 限定简单类名以指定文本开头。 */
    fun simpleNameStartsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.SIMPLE_NAME_STARTS_WITH to (value to ignoreCase),
            "simpleName startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { simpleClassName().startsWith(value, ignoreCase) }
    }

    /** 限定简单类名以指定文本结尾。 */
    fun simpleNameEndsWith(
        value: String,
        ignoreCase: Boolean = false,
    ) {
        addNameCondition(
            ClassCachePart.SIMPLE_NAME_ENDS_WITH to (value to ignoreCase),
            "simpleName endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else ""),
        ) { simpleClassName().endsWith(value, ignoreCase) }
    }

    /** 限定为 [parent] 的子类或同类。 */
    fun subclassOf(parent: Class<*>) {
        addCondition(ClassCachePart.SUBCLASS_OF to parent, "subclassOf=${parent.name}") { parent.isAssignableFrom(this) }
    }

    /** 要求类中存在符合条件的方法。 */
    fun hasMethod(query: MethodQuery.() -> Unit) {
        val plan = methodQuery(query).freeze(QueryResultMode.FIRST)
        addCondition(plan.cacheKey?.let { ClassCachePart.HAS_METHOD to it }, "hasMethod(${plan.description ?: "custom"})") {
            plan.acceptsCount(findMethodsMatching(this, plan).size)
        }
    }

    /** 要求类中存在符合条件的字段。 */
    fun hasField(query: FieldQuery.() -> Unit) {
        val plan = fieldQuery(query).freeze(QueryResultMode.FIRST)
        addCondition(plan.cacheKey?.let { ClassCachePart.HAS_FIELD to it }, "hasField(${plan.description ?: "custom"})") {
            plan.acceptsCount(findFieldsMatching(this, plan).size)
        }
    }

    /** 要求类中存在符合条件的构造器。 */
    fun hasConstructor(query: ConstructorQuery.() -> Unit) {
        val plan = constructorQuery(query).freeze(QueryResultMode.FIRST)
        addCondition(plan.cacheKey?.let { ClassCachePart.HAS_CONSTRUCTOR to it }, "hasConstructor(${plan.description ?: "custom"})") {
            plan.acceptsCount(findConstructorsMatching(this, plan).size)
        }
    }

    /** 添加自定义 Kotlin 条件。 */
    fun filter(condition: Class<*>.() -> Boolean) {
        addCondition(null, "customFilter") { QueryFilterContext.run { condition(this) } }
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Class<*>>) {
        filter { predicate.test(this) }
    }
}

internal fun classQuery(block: ClassQuery.() -> Unit): ClassQuery = ClassQuery().apply(block)

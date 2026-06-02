package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findAllFields
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
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

private data class ClassTextMatchKey(val value: String, val ignoreCase: Boolean)

private val classCachePartOrder = listOf(
    ClassCachePart.PACKAGE_NAME,
    ClassCachePart.PACKAGE_STARTS_WITH,
    ClassCachePart.PACKAGE_CONTAINS,
    ClassCachePart.PACKAGE_ENDS_WITH,
    ClassCachePart.NAME,
    ClassCachePart.NAME_STARTS_WITH,
    ClassCachePart.NAME_CONTAINS,
    ClassCachePart.NAME_ENDS_WITH,
    ClassCachePart.SIMPLE_NAME,
    ClassCachePart.SIMPLE_NAME_STARTS_WITH,
    ClassCachePart.SIMPLE_NAME_CONTAINS,
    ClassCachePart.SIMPLE_NAME_ENDS_WITH,
    ClassCachePart.SUBCLASS_OF,
    ClassCachePart.HAS_METHOD,
    ClassCachePart.HAS_FIELD,
    ClassCachePart.HAS_CONSTRUCTOR,
)

private fun classCacheKeyOf(parts: Map<ClassCachePart, Any>): List<Any> {
    val result = ArrayList<Any>(parts.size * 2)
    for (part in classCachePartOrder) {
        val value = parts[part] ?: continue
        result += part
        result += value
    }
    return result
}

private fun String.packagePart(): String =
    substringBeforeLast('.', missingDelimiterValue = "")

private fun String.simpleClassName(): String =
    substringAfterLast('.').substringAfterLast('$')

/**
 * 类查询条件。
 *
 * 类名条件会先执行，只有命中后才会加载 [Class] 并执行成员反推等较重条件。
 */
class ClassQuery internal constructor() : BaseQuery<Class<*>>() {
    private val nameConditions = mutableListOf<String.() -> Boolean>()
    private val classConditions = mutableListOf<Class<*>.() -> Boolean>()
    private val cacheParts = mutableMapOf<ClassCachePart, Any>()
    private val descriptions = mutableListOf<String>()
    private var cacheable = true

    /** 限定完整类名。 */
    fun name(value: String) {
        nameConditions += { this == value }
        cacheParts[ClassCachePart.NAME] = value
        descriptions += "name=$value"
    }

    /** 限定完整类名包含指定文本。 */
    fun nameContains(value: String, ignoreCase: Boolean = false) {
        nameConditions += { contains(value, ignoreCase) }
        cacheParts[ClassCachePart.NAME_CONTAINS] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "name contains \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定完整类名以指定文本开头。 */
    fun nameStartsWith(value: String, ignoreCase: Boolean = false) {
        nameConditions += { startsWith(value, ignoreCase) }
        cacheParts[ClassCachePart.NAME_STARTS_WITH] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "name startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定完整类名以指定文本结尾。 */
    fun nameEndsWith(value: String, ignoreCase: Boolean = false) {
        nameConditions += { endsWith(value, ignoreCase) }
        cacheParts[ClassCachePart.NAME_ENDS_WITH] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "name endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定包名。 */
    fun packageName(value: String) {
        nameConditions += { packagePart() == value }
        cacheParts[ClassCachePart.PACKAGE_NAME] = value
        descriptions += "package=$value"
    }

    /** 限定包名包含指定文本。 */
    fun packageContains(value: String, ignoreCase: Boolean = false) {
        nameConditions += { packagePart().contains(value, ignoreCase) }
        cacheParts[ClassCachePart.PACKAGE_CONTAINS] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "package contains \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定包名以指定文本开头。 */
    fun packageStartsWith(value: String, ignoreCase: Boolean = false) {
        nameConditions += { packagePart().startsWith(value, ignoreCase) }
        cacheParts[ClassCachePart.PACKAGE_STARTS_WITH] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "package startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定包名以指定文本结尾。 */
    fun packageEndsWith(value: String, ignoreCase: Boolean = false) {
        nameConditions += { packagePart().endsWith(value, ignoreCase) }
        cacheParts[ClassCachePart.PACKAGE_ENDS_WITH] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "package endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定简单类名。 */
    fun simpleName(value: String) {
        nameConditions += { simpleClassName() == value }
        cacheParts[ClassCachePart.SIMPLE_NAME] = value
        descriptions += "simpleName=$value"
    }

    /** 限定简单类名包含指定文本。 */
    fun simpleNameContains(value: String, ignoreCase: Boolean = false) {
        nameConditions += { simpleClassName().contains(value, ignoreCase) }
        cacheParts[ClassCachePart.SIMPLE_NAME_CONTAINS] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "simpleName contains \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定简单类名以指定文本开头。 */
    fun simpleNameStartsWith(value: String, ignoreCase: Boolean = false) {
        nameConditions += { simpleClassName().startsWith(value, ignoreCase) }
        cacheParts[ClassCachePart.SIMPLE_NAME_STARTS_WITH] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "simpleName startsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定简单类名以指定文本结尾。 */
    fun simpleNameEndsWith(value: String, ignoreCase: Boolean = false) {
        nameConditions += { simpleClassName().endsWith(value, ignoreCase) }
        cacheParts[ClassCachePart.SIMPLE_NAME_ENDS_WITH] = ClassTextMatchKey(value, ignoreCase)
        descriptions += "simpleName endsWith \"$value\"" + (if (ignoreCase) " ignoreCase" else "")
    }

    /** 限定为 [parent] 的子类或同类。 */
    fun subclassOf(parent: Class<*>) {
        classConditions += { parent.isAssignableFrom(this) }
        cacheParts[ClassCachePart.SUBCLASS_OF] = parent
        descriptions += "subclassOf=${parent.name}"
    }

    /** 要求类中存在符合条件的方法。 */
    fun hasMethod(query: MethodQuery.() -> Unit) {
        val builtQuery = methodQuery(query)
        classConditions += {
            val matches = findAllMethods(this) { applyFrom(builtQuery) }
            if (builtQuery.requiresSingleResult) matches.size == 1 else matches.isNotEmpty()
        }
        val memberKey = builtQuery.cacheKeyOrNull()
        cacheParts[ClassCachePart.HAS_METHOD] = memberKey?.let { listOf(it, builtQuery.requiresSingleResult) } ?: run {
            cacheable = false
            "customMethod"
        }
        descriptions += "hasMethod(${builtQuery.describe() ?: "custom"})"
    }

    /** 要求类中存在符合条件的字段。 */
    fun hasField(query: FieldQuery.() -> Unit) {
        val builtQuery = fieldQuery(query)
        classConditions += {
            val matches = findAllFields(this) { applyFrom(builtQuery) }
            if (builtQuery.requiresSingleResult) matches.size == 1 else matches.isNotEmpty()
        }
        val memberKey = builtQuery.cacheKeyOrNull()
        cacheParts[ClassCachePart.HAS_FIELD] = memberKey?.let { listOf(it, builtQuery.requiresSingleResult) } ?: run {
            cacheable = false
            "customField"
        }
        descriptions += "hasField(${builtQuery.describe() ?: "custom"})"
    }

    /** 要求类中存在符合条件的构造器。 */
    fun hasConstructor(query: ConstructorQuery.() -> Unit) {
        val builtQuery = constructorQuery(query)
        classConditions += {
            val matches = findAllConstructors(this) { applyFrom(builtQuery) }
            if (builtQuery.requiresSingleResult) matches.size == 1 else matches.isNotEmpty()
        }
        val memberKey = builtQuery.cacheKeyOrNull()
        cacheParts[ClassCachePart.HAS_CONSTRUCTOR] =
            memberKey?.let { listOf(it, builtQuery.requiresSingleResult) } ?: run {
            cacheable = false
            "customConstructor"
        }
        descriptions += "hasConstructor(${builtQuery.describe() ?: "custom"})"
    }

    /** 添加自定义 Kotlin 条件。 */
    fun filter(condition: Class<*>.() -> Boolean) {
        classConditions += { QueryFilterContext.run { condition(this) } }
        cacheable = false
        descriptions += "customFilter"
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Class<*>>) {
        filter { predicate.test(this) }
    }

    internal fun matchesName(className: String): Boolean =
        nameConditions.all { it(className) }

    internal fun matchesClass(clz: Class<*>): Boolean =
        classConditions.all { it(clz) }

    internal fun cacheKeyOrNull(): List<Any>? =
        cacheKeyOrManual(classCacheKeyOf(cacheParts), cacheable)

    internal fun describe(): String? =
        descriptions.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun MethodQuery.applyFrom(other: MethodQuery) {
    filter { other.matches(this) }
}

private fun FieldQuery.applyFrom(other: FieldQuery) {
    filter { other.matches(this) }
}

private fun ConstructorQuery.applyFrom(other: ConstructorQuery) {
    filter { other.matches(this) }
}

internal fun classQuery(block: ClassQuery.() -> Unit): ClassQuery =
    ClassQuery().apply(block)

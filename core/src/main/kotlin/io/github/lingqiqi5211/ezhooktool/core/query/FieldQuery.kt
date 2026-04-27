package io.github.lingqiqi5211.ezhooktool.core.query

import io.github.lingqiqi5211.ezhooktool.core.FieldCondition
import io.github.lingqiqi5211.ezhooktool.core.isStatic
import io.github.lingqiqi5211.ezhooktool.core.isTypeMatch
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.function.Predicate

private enum class FieldCachePart {
    NAME,
    NAME_CONTAINS,
    NAME_STARTS_WITH,
    NAME_ENDS_WITH,
    TYPE,
    TYPE_EXTENDS_FROM,
    IS_STATIC,
    FLAGS,
}

private data class FieldTextMatchKey(val value: String, val ignoreCase: Boolean)

private val fieldCachePartOrder = listOf(
    FieldCachePart.NAME,
    FieldCachePart.NAME_CONTAINS,
    FieldCachePart.NAME_STARTS_WITH,
    FieldCachePart.NAME_ENDS_WITH,
    FieldCachePart.TYPE,
    FieldCachePart.TYPE_EXTENDS_FROM,
    FieldCachePart.IS_STATIC,
    FieldCachePart.FLAGS,
)

private fun fieldCacheKeyOf(parts: Map<FieldCachePart, Any>): List<Any> {
    val result = ArrayList<Any>(parts.size * 2)
    for (part in fieldCachePartOrder) {
        val value = parts[part] ?: continue
        result += part
        result += value
    }
    return result
}

private fun Field.isSyntheticField(): Boolean = modifiers and 0x00001000 != 0

/**
 * 字段查询条件。
 *
 * 用在 `findField`、`findFieldOrNull`、`findAllFields` 的查询块里。
 * 多个条件会同时生效，全部满足才算匹配。
 *
 * ```kotlin
 * val field = clazz.findField {
 *     name("mContext")
 *     type(Context::class.java)
 * }
 * ```
 */
class FieldQuery internal constructor() {
    private val conditions = mutableListOf<FieldCondition>()
    private val cacheParts = mutableMapOf<FieldCachePart, Any>()
    private val flags = mutableMapOf<String, Boolean>()
    private var cacheable = true
    private var searchSuperSet = false
    private var searchSuperValue: Boolean? = null

    /** 限定字段名。 */
    fun name(value: String) {
        conditions += { name == value }
        cacheParts[FieldCachePart.NAME] = value
    }

    /** 限定字段名包含指定文本。 */
    fun nameContains(value: String, ignoreCase: Boolean = false) {
        conditions += { name.contains(value, ignoreCase) }
        cacheParts[FieldCachePart.NAME_CONTAINS] = FieldTextMatchKey(value, ignoreCase)
    }

    /** 限定字段名以指定文本开头。 */
    fun nameStartsWith(value: String, ignoreCase: Boolean = false) {
        conditions += { name.startsWith(value, ignoreCase) }
        cacheParts[FieldCachePart.NAME_STARTS_WITH] = FieldTextMatchKey(value, ignoreCase)
    }

    /** 限定字段名以指定文本结尾。 */
    fun nameEndsWith(value: String, ignoreCase: Boolean = false) {
        conditions += { name.endsWith(value, ignoreCase) }
        cacheParts[FieldCachePart.NAME_ENDS_WITH] = FieldTextMatchKey(value, ignoreCase)
    }

    /** 限定字段类型。 */
    fun type(value: Class<*>) {
        conditions += { type == value }
        cacheParts[FieldCachePart.TYPE] = value
    }

    /** 限定字段类型是 [value] 本身或子类。 */
    fun typeExtendsFrom(value: Class<*>) {
        conditions += { isTypeMatch(type, value) }
        cacheParts[FieldCachePart.TYPE_EXTENDS_FROM] = value
    }

    /** 限定为 static 字段。 */
    fun isStatic() {
        isStatic(true)
    }

    /** 限定是否为 static 字段。 */
    fun isStatic(value: Boolean) {
        conditions += { this.isStatic == value }
        cacheParts[FieldCachePart.IS_STATIC] = value
    }

    /** 限定为非 static 字段。 */
    fun notStatic() {
        isStatic(false)
    }

    /** 限定为 public 字段。 */
    fun isPublic() {
        flag("public", true) { Modifier.isPublic(modifiers) }
    }

    /** 限定为非 public 字段。 */
    fun notPublic() {
        flag("public", false) { Modifier.isPublic(modifiers) }
    }

    /** 限定为 private 字段。 */
    fun isPrivate() {
        flag("private", true) { Modifier.isPrivate(modifiers) }
    }

    /** 限定为非 private 字段。 */
    fun notPrivate() {
        flag("private", false) { Modifier.isPrivate(modifiers) }
    }

    /** 限定为 protected 字段。 */
    fun isProtected() {
        flag("protected", true) { Modifier.isProtected(modifiers) }
    }

    /** 限定为非 protected 字段。 */
    fun notProtected() {
        flag("protected", false) { Modifier.isProtected(modifiers) }
    }

    /** 限定为 final 字段。 */
    fun isFinal() {
        flag("final", true) { Modifier.isFinal(modifiers) }
    }

    /** 限定为非 final 字段。 */
    fun notFinal() {
        flag("final", false) { Modifier.isFinal(modifiers) }
    }

    /** 限定为 volatile 字段。 */
    fun isVolatile() {
        flag("volatile", true) { Modifier.isVolatile(modifiers) }
    }

    /** 限定为非 volatile 字段。 */
    fun notVolatile() {
        flag("volatile", false) { Modifier.isVolatile(modifiers) }
    }

    /** 限定为 transient 字段。 */
    fun isTransient() {
        flag("transient", true) { Modifier.isTransient(modifiers) }
    }

    /** 限定为非 transient 字段。 */
    fun notTransient() {
        flag("transient", false) { Modifier.isTransient(modifiers) }
    }

    /** 限定为 enum 常量字段。 */
    fun isEnumConstant() {
        flag("enumConstant", true) { this.isEnumConstant }
    }

    /** 限定为非 enum 常量字段。 */
    fun notEnumConstant() {
        flag("enumConstant", false) { this.isEnumConstant }
    }

    /** 限定为 synthetic 字段。 */
    fun isSynthetic() {
        flag("synthetic", true) { isSyntheticField() }
    }

    /** 限定为非 synthetic 字段。 */
    fun notSynthetic() {
        flag("synthetic", false) { isSyntheticField() }
    }

    /**
     * 只在当前类中查找。
     *
     * 等同于调用 `findField(findSuper = false) { ... }`。
     */
    fun currentClassOnly() {
        searchSuperSet = true
        searchSuperValue = false
    }

    /**
     * 查找当前类和全部父类。
     *
     * 等同于调用 `findField(findSuper = true) { ... }`。
     */
    fun includeSuper() {
        searchSuperSet = true
        searchSuperValue = true
    }

    /** 添加自定义 Kotlin 条件。 */
    fun filter(condition: FieldCondition) {
        conditions += condition
        cacheable = false
    }

    /** 添加 Java `Predicate` 条件。 */
    fun filter(predicate: Predicate<Field>) {
        conditions += { predicate.test(this) }
        cacheable = false
    }

    private fun flag(name: String, value: Boolean, condition: Field.() -> Boolean) {
        conditions += { condition(this) == value }
        flags[name] = value
        cacheParts[FieldCachePart.FLAGS] = flags.toSortedMap().toList()
    }

    internal fun effectiveFindSuper(defaultValue: Boolean?): Boolean? =
        if (searchSuperSet) searchSuperValue else defaultValue

    internal fun cacheKeyOrNull(): List<Any>? =
        if (cacheable) fieldCacheKeyOf(cacheParts) else null

    internal fun matches(field: Field): Boolean = conditions.all { it(field) }
}

internal fun fieldExactCacheKeys(field: Field): List<List<Any>> {
    val exact = mapOf(
        FieldCachePart.NAME to field.name,
        FieldCachePart.TYPE to field.type,
        FieldCachePart.IS_STATIC to field.isStatic,
    )

    return listOf(fieldCacheKeyOf(exact))
}

internal fun fieldQuery(block: FieldQuery.() -> Unit): FieldQuery =
    FieldQuery().apply(block)

internal fun fieldCondition(query: FieldQuery): FieldCondition =
    { query.matches(this) }

internal fun fieldCondition(block: FieldQuery.() -> Unit): FieldCondition =
    fieldCondition(fieldQuery(block))

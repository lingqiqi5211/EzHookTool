@file:JvmName("FieldUtils")

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.fieldCondition
import io.github.lingqiqi5211.ezhooktool.core.query.fieldExactCacheKeys
import io.github.lingqiqi5211.ezhooktool.core.query.fieldQuery
import java.lang.reflect.Field

/**
 * 字段查找条件。以 Field 为 receiver。
 *
 * ```kotlin
 * findField(clz) {
 *     name("mValue")
 *     type(String::class.java)
 * }
 * ```
 */
typealias FieldCondition = Field.() -> Boolean

// ═══════════════════════ Internal: Smart Search ═══════════════════════

private fun searchFields(
    clz: Class<*>,
    findSuper: Boolean?,
    collectAll: Boolean,
    condition: FieldCondition,
): List<Field> {
    val results = mutableListOf<Field>()

    when (findSuper) {
        false -> {
            for (f in EzReflect.memberResolver.fieldsOf(clz)) {
                if (condition(f)) {
                    f.isAccessible = true
                    if (!collectAll) return listOf(f)
                    results.add(f)
                }
            }
        }

        true -> {
            var current: Class<*>? = clz
            while (current != null) {
                for (f in EzReflect.memberResolver.fieldsOf(current)) {
                    if (condition(f)) {
                        f.isAccessible = true
                        if (!collectAll) return listOf(f)
                        results.add(f)
                    }
                }
                current = current.superclass
            }
        }

        null -> {
            var current: Class<*>? = clz
            while (current != null) {
                for (f in EzReflect.memberResolver.fieldsOf(current)) {
                    if (condition(f)) {
                        f.isAccessible = true
                        if (!collectAll) return listOf(f)
                        results.add(f)
                    }
                }
                if (!collectAll && results.isEmpty()) {
                    current = current.superclass
                } else if (collectAll && results.isNotEmpty()) {
                    return results
                } else if (collectAll) {
                    current = current.superclass
                } else {
                    break
                }
            }
        }
    }
    return results
}

private fun getFieldCandidates(clz: Class<*>): List<String> {
    if (!EzReflect.debugMode) return emptyList()
    return EzReflect.memberResolver.fieldsOf(clz).map { it.toReadableString() }
}

// ═══════════════════════ Cache Keys ═══════════════════════

private data class FieldCacheKey(
    val queryKey: List<Any>,
    val findSuper: Boolean?,
)

private data class FieldNameCacheKey(
    val name: String,
    val isStatic: Boolean?,
    val fieldType: Class<*>?,
)

private data class DeclaringFieldCacheKey(
    val owner: Class<*>,
    val key: FieldCacheKey,
)

private fun cacheExactFields(
    searchClass: Class<*>,
    findSuper: Boolean?,
    fields: List<Field>,
    cacheSearchClass: Boolean,
) {
    if (!EzReflect.cacheEnabled) return
    val searchKeys = HashSet<FieldCacheKey>()
    val declaringKeys = HashSet<DeclaringFieldCacheKey>()

    for (field in fields) {
        for (queryKey in fieldExactCacheKeys(field)) {
            if (cacheSearchClass) {
                val key = FieldCacheKey(queryKey, findSuper)
                if (searchKeys.add(key)) {
                    EzReflect.cachePut(searchClass, ReflectCacheBucket.FIELD, key, field)
                }
            }

            val currentClassKey = FieldCacheKey(queryKey, false)
            val smartKey = FieldCacheKey(queryKey, null)
            if (declaringKeys.add(DeclaringFieldCacheKey(field.declaringClass, currentClassKey))) {
                EzReflect.cachePut(field.declaringClass, ReflectCacheBucket.FIELD, currentClassKey, field)
            }
            if (declaringKeys.add(DeclaringFieldCacheKey(field.declaringClass, smartKey))) {
                EzReflect.cachePut(field.declaringClass, ReflectCacheBucket.FIELD, smartKey, field)
            }
        }
    }
}

// ═══════════════════════ 按查询条件查找 (Class) ═══════════════════════

/**
 * 按查询条件查找字段。
 *
 * ```kotlin
 * val f = findField(clz) {
 *     name("mCallback")
 *     type(Runnable::class.java)
 * }
 * ```
 *
 * @param clz        目标类
 * @param findSuper  null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query      查询条件块
 * @return 第一个匹配的 Field（已 setAccessible）
 * @throws MemberNotFoundException 找不到时
 */
fun findField(
    clz: Class<*>,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field {
    val builtQuery = fieldQuery(query)
    val effectiveFindSuper = builtQuery.effectiveFindSuper(findSuper)
    return findFieldOrNull(clz, findSuper, builtQuery)
        ?: throw MemberNotFoundException(
            memberType = MemberType.FIELD,
            targetClass = clz.name,
            searchedSuper = effectiveFindSuper != false,
            candidates = getFieldCandidates(clz)
        )
}

/**
 * 按查询条件查找字段，找不到返回 null。
 *
 * @param clz 目标类
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
fun findFieldOrNull(
    clz: Class<*>,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field? {
    return findFieldOrNull(clz, findSuper, fieldQuery(query))
}

private fun findFieldOrNull(
    clz: Class<*>,
    findSuper: Boolean?,
    query: FieldQuery,
): Field? {
    val effectiveFindSuper = query.effectiveFindSuper(findSuper)
    val condition = fieldCondition(query)
    val queryKey = query.cacheKeyOrNull()
    if (EzReflect.cacheEnabled && queryKey != null) {
        val key = FieldCacheKey(queryKey, effectiveFindSuper)
        val cached = EzReflect.cacheGet(clz, ReflectCacheBucket.FIELD, key)
        if (cached is Field) return cached
    }
    val result = searchFields(clz, effectiveFindSuper, collectAll = false, condition).firstOrNull()
    if (result != null && EzReflect.cacheEnabled && queryKey != null) {
        EzReflect.cachePut(clz, ReflectCacheBucket.FIELD, FieldCacheKey(queryKey, effectiveFindSuper), result)
    }
    return result
}

/**
 * 按类名查找字段。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
fun findField(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field = findField(loadClass(className, classLoader), findSuper, query)

/**
 * 按类名查找字段，找不到返回 null。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
fun findFieldOrNull(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field? {
    val clz = loadClassOrNull(className, classLoader) ?: return null
    return findFieldOrNull(clz, findSuper, query)
}

private fun findAllFieldsMatching(
    clz: Class<*>,
    findSuper: Boolean? = null,
    condition: FieldCondition,
): List<Field> = searchFields(clz, findSuper, collectAll = true, condition)

/**
 * 查找全部字段。
 *
 * @param clz 目标类
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 */
fun findAllFields(
    clz: Class<*>,
    findSuper: Boolean? = null,
): List<Field> = findAllFieldsMatching(clz, findSuper) { true }

/**
 * 按查询条件查找字段。
 *
 * @param clz 目标类
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
fun findAllFields(
    clz: Class<*>,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): List<Field> {
    val builtQuery = fieldQuery(query)
    val effectiveFindSuper = builtQuery.effectiveFindSuper(findSuper)
    val cacheSearchClass = builtQuery.cacheKeyOrNull() != null
    findFieldOrNull(clz, findSuper, builtQuery) ?: return emptyList()
    val results = findAllFieldsMatching(
        clz = clz,
        findSuper = effectiveFindSuper,
        condition = fieldCondition(builtQuery),
    )
    cacheExactFields(clz, effectiveFindSuper, results, cacheSearchClass)
    return results
}

/**
 * 按类名查找全部字段。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 */
fun findAllFields(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
): List<Field> = findAllFields(loadClass(className, classLoader), findSuper)

/**
 * 按类名和查询条件查找字段。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
fun findAllFields(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): List<Field> = findAllFields(loadClass(className, classLoader), findSuper, query)

// ═══════════════════════ 按名称获取 ═══════════════════════

/**
 * 按名称获取实例/静态字段。
 *
 * ```kotlin
 * val f = instance.field("mValue")
 * ```
 *
 * @param fieldName 字段名
 * @param isStatic 是否按静态字段处理
 * @param fieldType 可选字段类型，用于进一步约束匹配结果
 */
fun Any.field(
    fieldName: String,
    isStatic: Boolean = false,
    fieldType: Class<*>? = null,
): Field {
    return fieldOrNull(fieldName, isStatic, fieldType)
        ?: throw MemberNotFoundException(
            memberType = MemberType.FIELD,
            targetClass = javaClass.name,
            searchedSuper = true,
            conditionDesc = "name=$fieldName" +
                    (if (fieldType != null) ", type=${fieldType.simpleName}" else ""),
            candidates = getFieldCandidates(javaClass)
        )
}

/**
 * 按名称获取字段，找不到返回 null。
 *
 * @param fieldName 字段名
 * @param isStatic 是否按静态字段处理
 * @param fieldType 可选字段类型，用于进一步约束匹配结果
 */
fun Any.fieldOrNull(
    fieldName: String,
    isStatic: Boolean = false,
    fieldType: Class<*>? = null,
): Field? {
    val clz = if (this is Class<*>) this else javaClass
    if (EzReflect.cacheEnabled) {
        val key = FieldNameCacheKey(fieldName, isStatic, fieldType)
        val cached = EzReflect.cacheGet(clz, ReflectCacheBucket.FIELD, key)
        if (cached is Field) return cached
    }
    var current: Class<*>? = clz
    while (current != null) {
        try {
            val f = current.getDeclaredField(fieldName)
            if (fieldType != null && f.type != fieldType) {
                current = current.superclass
                continue
            }
            f.isAccessible = true
            if (EzReflect.cacheEnabled) {
                EzReflect.cachePut(
                    clz,
                    ReflectCacheBucket.FIELD,
                    FieldNameCacheKey(fieldName, isStatic, fieldType),
                    f,
                )
            }
            return f
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    return null
}

/**
 * 获取静态字段。
 *
 * ```kotlin
 * val f = clz.staticField("TAG")
 * ```
 *
 * @param name 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
fun Class<*>.staticField(name: String, type: Class<*>? = null): Field =
    this.field(name, isStatic = true, fieldType = type)

/**
 * 获取静态字段，找不到返回 null。
 *
 * @param name 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
fun Class<*>.staticFieldOrNull(name: String, type: Class<*>? = null): Field? =
    this.fieldOrNull(name, isStatic = true, fieldType = type)

// ═══════════════════════ 组合态链式 (String 出发) ═══════════════════════

/**
 * 从类名直接查找字段的便捷写法，等价于 `loadClass(name).findField { ... }`。
 * 适合一次性查找场景；多次操作同一个类时推荐先显式 loadClass / loadClassFirst。
 *
 * ```kotlin
 * val f = "com.example.Target".findField { name("mValue") }
 * ```
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@JvmName("findFieldFromString")
fun String.findField(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field = findField(loadClass(this, classLoader), findSuper, query)

/**
 * 从类名查找字段，找不到返回 null。
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@JvmName("findFieldOrNullFromString")
fun String.findFieldOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field? {
    val clz = loadClassOrNull(this, classLoader) ?: return null
    return findFieldOrNull(clz, findSuper, query)
}

/**
 * 从类名查找全部字段。
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 */
@JvmName("findAllFieldsFromString")
fun String.findAllFields(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
): List<Field> = findAllFields(loadClass(this, classLoader), findSuper)

/**
 * 从类名按查询条件查找字段。
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@JvmName("findAllFieldsFromStringWithQuery")
fun String.findAllFields(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): List<Field> = findAllFields(loadClass(this, classLoader), findSuper, query)

// ═══════════════════════ 组合态链式 (Class 出发) ═══════════════════════

/**
 * 从 Class 对象直接查找字段。
 *
 * ```kotlin
 * val f = clz.findField {
 *     name("mCallback")
 *     type(Runnable::class.java)
 * }
 * ```
 *
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@JvmName("findFieldFromClass")
fun Class<*>.findField(
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field = findField(this, findSuper, query)

/**
 * 从 Class 对象查找字段，找不到返回 null。
 *
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@JvmName("findFieldOrNullFromClass")
fun Class<*>.findFieldOrNull(
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): Field? = findFieldOrNull(this, findSuper, query)

/**
 * 从 Class 对象查找全部字段。
 *
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 */
@JvmName("findAllFieldsFromClass")
fun Class<*>.findAllFields(
    findSuper: Boolean? = null,
): List<Field> = findAllFields(this, findSuper)

/**
 * 从 Class 对象按查询条件查找字段。
 *
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@JvmName("findAllFieldsFromClassWithQuery")
fun Class<*>.findAllFields(
    findSuper: Boolean? = null,
    query: FieldQuery.() -> Unit,
): List<Field> = findAllFields(this, findSuper, query)

// ═══════════════════════ 实例字段读取 ═══════════════════════

/**
 * 按名称读取字段值。
 *
 * ```kotlin
 * val value = instance.getField("mValue")
 * ```
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
fun Any.getField(fieldName: String, type: Class<*>? = null): Any? {
    return readFieldValue(field(fieldName, fieldType = type), this)
}

/**
 * 类型安全的字段读取。
 *
 * ```kotlin
 * val name: String = instance.getFieldAs("userName")
 * ```
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.getFieldAs(fieldName: String, type: Class<*>? = null): T? =
    getField(fieldName, type) as T?

/**
 * 按名称读取字段值，找不到返回 null（不抛异常）。
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
fun Any.getFieldOrNull(fieldName: String, type: Class<*>? = null): Any? {
    val f = fieldOrNull(fieldName, fieldType = type) ?: return null
    return readFieldValue(f, this)
}

/**
 * 类型安全的字段读取，找不到返回 null。
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.getFieldOrNullAs(fieldName: String, type: Class<*>? = null): T? =
    getFieldOrNull(fieldName, type) as T?

/**
 * 按字段类型获取值。
 *
 * ```kotlin
 * val callback: Runnable? = instance.getFieldByType(Runnable::class.java)
 * ```
 *
 * @param type 要匹配的字段类型
 * @param isStatic 是否按静态字段处理
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.getFieldByType(type: Class<*>, isStatic: Boolean = false): T? {
    val f = findFirstFieldByType(this, type, isStatic)
        ?: throw MemberNotFoundException(
            memberType = MemberType.FIELD,
            targetClass = ownerClass().name,
            searchedSuper = true,
            conditionDesc = "type=${type.simpleName}, isStatic=$isStatic",
            candidates = emptyList(),
        )
    return readFieldValue(f, if (isStatic) null else this) as T?
}

/**
 * 按字段类型获取值，找不到返回 null。
 *
 * @param type 要匹配的字段类型
 * @param isStatic 是否按静态字段处理
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.getFieldByTypeOrNull(type: Class<*>, isStatic: Boolean = false): T? {
    val f = findFirstFieldByType(this, type, isStatic) ?: return null
    return readFieldValue(f, if (isStatic) null else this) as T?
}

/**
 * 按查询条件查找字段并直接获取值。
 *
 * ```kotlin
 * val value = instance.findFieldValue {
 *     filter { name.startsWith("m") }
 *     type(String::class.java)
 * }
 * ```
 *
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
fun Any.findFieldValue(findSuper: Boolean? = null, query: FieldQuery.() -> Unit): Any? {
    return readFieldValue(findField(ownerClass(), findSuper, query), if (this is Class<*>) null else this)
}

/**
 * 类型安全的查询条件查找字段值。
 *
 * @param findSuper null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query 查询条件块
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.findFieldValueAs(findSuper: Boolean? = null, query: FieldQuery.() -> Unit): T? =
    findFieldValue(findSuper, query) as T?

// ═══════════════════════ 静态字段读取 ═══════════════════════

/**
 * 读取静态字段值。
 *
 * ```kotlin
 * val tag: String = targetClass.getStaticFieldAs("TAG")
 * ```
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
fun Class<*>.getStaticField(fieldName: String, type: Class<*>? = null): Any? {
    return readFieldValue(staticField(fieldName, type), null)
}

/**
 * 类型安全的静态字段读取。
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.getStaticFieldAs(fieldName: String, type: Class<*>? = null): T? =
    getStaticField(fieldName, type) as T?

/**
 * 读取静态字段值，找不到返回 null。
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
fun Class<*>.getStaticFieldOrNull(fieldName: String, type: Class<*>? = null): Any? {
    val f = staticFieldOrNull(fieldName, type) ?: return null
    return readFieldValue(f, null)
}

/**
 * 类型安全的静态字段读取，找不到返回 null。
 *
 * @param fieldName 字段名
 * @param type 可选字段类型，用于进一步约束匹配结果
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.getStaticFieldOrNullAs(fieldName: String, type: Class<*>? = null): T? =
    getStaticFieldOrNull(fieldName, type) as T?

// ═══════════════════════ Field 扩展 ═══════════════════════

/** Field 对象上的类型安全读取。 */
@Suppress("UNCHECKED_CAST")
fun <T> Field.getAs(obj: Any?): T? {
    isAccessible = true
    return get(obj) as T?
}

/** 读取静态字段值（类型安全）。 */
@Suppress("UNCHECKED_CAST")
fun <T> Field.getStaticAs(): T? {
    isAccessible = true
    return get(null) as T?
}

// ═══════════════════════ 实例字段写入 ═══════════════════════

/**
 * 按名称设置字段值。
 *
 * ```kotlin
 * instance.putField("mValue", "newValue")
 * ```
 *
 * @param fieldName 字段名
 * @param value 要写入的值
 * @param fieldType 可选字段类型，用于进一步约束匹配结果
 */
fun Any.putField(fieldName: String, value: Any?, fieldType: Class<*>? = null) {
    writeFieldValue(field(fieldName, fieldType = fieldType), this, value)
}

/**
 * 用 Field 对象设置字段值。
 *
 * @param field 要写入的字段
 * @param value 要写入的值
 */
fun Any.putField(field: Field, value: Any?) {
    writeFieldValue(field, this, value)
}

// ═══════════════════════ 静态字段写入 ═══════════════════════

/**
 * 设置静态字段值。
 *
 * ```kotlin
 * targetClass.putStaticField("sInstance", null)
 * ```
 *
 * @param fieldName 字段名
 * @param value 要写入的值
 * @param fieldType 可选字段类型，用于进一步约束匹配结果
 */
fun Class<*>.putStaticField(fieldName: String, value: Any?, fieldType: Class<*>? = null) {
    writeFieldValue(staticField(fieldName, fieldType), null, value)
}

/**
 * 用 Field 对象设置静态字段值。
 *
 * @param field 要写入的字段
 * @param value 要写入的值
 */
fun Class<*>.putStaticField(field: Field, value: Any?) {
    writeFieldValue(field, null, value)
}

/**
 * Field 扩展：设置静态字段值。
 *
 * @param value 要写入的值
 */
fun Field.setStatic(value: Any?) {
    writeFieldValue(this, null, value)
}

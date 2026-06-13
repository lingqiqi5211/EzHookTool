@file:JvmName("ConstructorUtils")

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.constructorCondition
import io.github.lingqiqi5211.ezhooktool.core.query.constructorExactCacheKeys
import io.github.lingqiqi5211.ezhooktool.core.query.constructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.QueryFilterContext
import java.lang.reflect.Constructor

/**
 * 构造器查找条件。以 Constructor 为 receiver。
 */
typealias ConstructorCondition = Constructor<*>.() -> Boolean

private fun getConstructorCandidates(clz: Class<*>): List<String> {
    if (!EzReflect.debugMode) return emptyList()
    return EzReflect.memberResolver.constructorsOf(clz).map { it.toReadableString() }
}

private data class ConstructorCacheKey(
    val queryKey: List<Any>,
    val resultMode: String = "first",
)

private data class AllConstructorsCacheKey(
    val queryKey: List<Any>,
)

private fun cacheExactConstructors(clz: Class<*>, constructors: List<Constructor<*>>) {
    if (!EzReflect.cacheEnabled) return
    val seenKeys = HashSet<ConstructorCacheKey>()
    for (constructor in constructors) {
        for (queryKey in constructorExactCacheKeys(constructor)) {
            val key = ConstructorCacheKey(queryKey)
            if (seenKeys.add(key)) {
                EzReflect.cachePut(clz, ReflectCacheBucket.CONSTRUCTOR, key, constructor)
            }
        }
    }
}

private fun findAllConstructorsMatching(
    clz: Class<*>,
    condition: ConstructorCondition,
    collectAll: Boolean = true,
    maxResults: Int? = null,
): List<Constructor<*>> {
    val results = mutableListOf<Constructor<*>>()
    for (ctor in EzReflect.memberResolver.constructorsOf(clz)) {
        if (condition(ctor)) {
            ctor.isAccessible = true
            if (!collectAll) return listOf(ctor)
            results.add(ctor)
            if (maxResults != null && results.size >= maxResults) return results
        }
    }
    return results
}

/**
 * 按查询条件查找构造器。
 *
 * ```kotlin
 * val ctor = findConstructor(clz) {
 *     paramCount(2)
 *     params(String::class.java, Int::class.java)
 * }
 * ```
 *
 * @param clz 目标类
 * @param query 查询条件块
 */
fun findConstructor(
    clz: Class<*>,
    query: ConstructorQuery.() -> Unit,
): Constructor<*> {
    QueryFilterContext.warnNestedFind("findConstructor")
    val builtQuery = constructorQuery(query)
    return findConstructorOrNull(clz, builtQuery)
        ?: throw MemberNotFoundException(
            memberType = MemberType.CONSTRUCTOR,
            targetClass = clz.name,
            searchedSuper = false,
            conditionDesc = builtQuery.describe(),
            candidates = getConstructorCandidates(clz)
        )
}

/**
 * 按查询条件查找构造器，找不到返回 null。
 */
fun findConstructorOrNull(
    clz: Class<*>,
    query: ConstructorQuery.() -> Unit,
): Constructor<*>? {
    QueryFilterContext.warnNestedFind("findConstructorOrNull")
    return findConstructorOrNull(clz, constructorQuery(query))
}

private fun findConstructorOrNull(
    clz: Class<*>,
    query: ConstructorQuery,
): Constructor<*>? {
    val condition = constructorCondition(query)
    val queryKey = query.cacheKeyOrNull()
    if (EzReflect.cacheEnabled && queryKey != null) {
        val key = ConstructorCacheKey(
            queryKey = queryKey,
            resultMode = if (query.requiresSingleResult) "single" else "first",
        )
        val cached = EzReflect.cacheGet(clz, ReflectCacheBucket.CONSTRUCTOR, key)
        if (cached is Constructor<*>) return cached
    }
    val results = findAllConstructorsMatching(
        clz = clz,
        condition = condition,
        collectAll = query.requiresSingleResult,
        maxResults = if (query.requiresSingleResult) 2 else null,
    )
    if (query.requiresSingleResult && results.size > 1) {
        throw SingleResultExpectedException(
            target = "constructor in ${clz.name}",
            conditionDesc = query.describe(),
        )
    }
    val result = results.firstOrNull()
    if (result != null && EzReflect.cacheEnabled && queryKey != null) {
        EzReflect.cachePut(
            clz,
            ReflectCacheBucket.CONSTRUCTOR,
            ConstructorCacheKey(
                queryKey = queryKey,
                resultMode = if (query.requiresSingleResult) "single" else "first",
            ),
            result,
        )
    }
    return result
}

/**
 * 按类名查找构造器。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param query 查询条件块
 */
fun findConstructor(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ConstructorQuery.() -> Unit,
): Constructor<*> = findConstructor(loadClass(className, classLoader), query)

/**
 * 按类名查找构造器，找不到返回 null。
 */
fun findConstructorOrNull(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ConstructorQuery.() -> Unit,
): Constructor<*>? {
    val clz = loadClassOrNull(className, classLoader) ?: return null
    return findConstructorOrNull(clz, query)
}

/**
 * 查找全部构造器。
 */
fun findAllConstructors(clz: Class<*>): List<Constructor<*>> =
    run {
        QueryFilterContext.warnNestedFind("findAllConstructors")
        findAllConstructorsMatching(clz, condition = { true })
    }

/**
 * 按查询条件查找构造器。
 */
fun findAllConstructors(
    clz: Class<*>,
    query: ConstructorQuery.() -> Unit,
): List<Constructor<*>> {
    QueryFilterContext.warnNestedFind("findAllConstructors")
    val builtQuery = constructorQuery(query)
    val queryKey = builtQuery.cacheKeyOrNull()
    if (EzReflect.cacheEnabled && queryKey != null) {
        val key = AllConstructorsCacheKey(queryKey)
        val cached = EzReflect.cacheGet(clz, ReflectCacheBucket.CONSTRUCTOR, key)
        if (cached is List<*>) {
            @Suppress("UNCHECKED_CAST")
            return cached as List<Constructor<*>>
        }
    }
    val results = findAllConstructorsMatching(clz, constructorCondition(builtQuery))
    if (EzReflect.cacheEnabled && queryKey != null) {
        EzReflect.cachePut(clz, ReflectCacheBucket.CONSTRUCTOR, AllConstructorsCacheKey(queryKey), results)
    }
    cacheExactConstructors(clz, results)
    return results
}

/**
 * 按类名查找全部构造器。
 */
fun findAllConstructors(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
): List<Constructor<*>> = findAllConstructors(loadClass(className, classLoader))

/**
 * 按类名和查询条件查找构造器。
 */
fun findAllConstructors(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ConstructorQuery.() -> Unit,
): List<Constructor<*>> = findAllConstructors(loadClass(className, classLoader), query)

/**
 * 从类名直接查找构造器。
 */
@JvmName("findConstructorByString")
fun String.findConstructor(
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ConstructorQuery.() -> Unit,
): Constructor<*> = findConstructor(loadClass(this, classLoader), query)

/**
 * 从类名查找构造器，找不到返回 null。
 */
@JvmName("findConstructorOrNullByString")
fun String.findConstructorOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ConstructorQuery.() -> Unit,
): Constructor<*>? {
    val clz = loadClassOrNull(this, classLoader) ?: return null
    return findConstructorOrNull(clz, query)
}

/**
 * 从类名查找全部构造器。
 */
@JvmName("findAllConstructorsFromString")
fun String.findAllConstructors(
    classLoader: ClassLoader = EzReflect.classLoader,
): List<Constructor<*>> = findAllConstructors(loadClass(this, classLoader))

/**
 * 从类名按查询条件查找构造器。
 */
@JvmName("findAllConstructorsFromStringWithQuery")
fun String.findAllConstructors(
    classLoader: ClassLoader = EzReflect.classLoader,
    query: ConstructorQuery.() -> Unit,
): List<Constructor<*>> = findAllConstructors(loadClass(this, classLoader), query)

/**
 * 从 Class 对象直接查找构造器。
 */
@JvmName("findConstructorByClass")
fun Class<*>.findConstructor(query: ConstructorQuery.() -> Unit): Constructor<*> =
    findConstructor(this, query)

/**
 * 从 Class 对象查找构造器，找不到返回 null。
 */
@JvmName("findConstructorOrNullByClass")
fun Class<*>.findConstructorOrNull(query: ConstructorQuery.() -> Unit): Constructor<*>? =
    findConstructorOrNull(this, query)

/**
 * 从 Class 对象查找全部构造器。
 */
@JvmName("findAllConstructorsFromClass")
fun Class<*>.findAllConstructors(): List<Constructor<*>> =
    findAllConstructors(this)

/**
 * 从 Class 对象按查询条件查找构造器。
 */
@JvmName("findAllConstructorsFromClassWithQuery")
fun Class<*>.findAllConstructors(query: ConstructorQuery.() -> Unit): List<Constructor<*>> =
    findAllConstructors(this, query)

/**
 * 创建实例（精确参数类型）。
 *
 * ```kotlin
 * val obj = clz.instantiate()
 * val obj = clz.instantiate(args("param1", 42), argTypes(String::class.java, Int::class.java))
 * ```
 *
 * **不要使用 `clz.newInstance()` 的无参写法**：Kotlin 重载解析会优先选 JDK `Class.newInstance()`，
 * 与本扩展函数行为不同（不会 setAccessible、JDK 9+ 已 @Deprecated）。
 * 使用 [instantiate] 可以避免这个陷阱。
 *
 * @param args 构造器参数值包装
 * @param argTypes 构造器参数类型；为空时会根据 [args] 自动推断
 */
fun Class<*>.instantiate(args: Args = args(), argTypes: ArgTypes = argTypes()): Any {
    val types = if (argTypes.types.isNotEmpty()) argTypes.types
    else if (args.args.isEmpty()) emptyArray()
    else inferArgTypes(args.args)
    val ctor = getDeclaredConstructor(*types)
    ctor.isAccessible = true
    return ctor.newInstance(*args.args)
}

/**
 * [instantiate] 的旧名字。仅供已有调用兼容；新代码请用 [instantiate]，避免无参写法落入 JDK `Class.newInstance()`。
 */
@Deprecated(
    message = "改用 instantiate(...)；无参写法 clz.newInstance() 会落到 JDK 实现，行为不同",
    replaceWith = ReplaceWith("instantiate(args, argTypes)"),
    level = DeprecationLevel.WARNING,
)
fun Class<*>.newInstance(args: Args = args(), argTypes: ArgTypes = argTypes()): Any =
    instantiate(args, argTypes)

/**
 * 类型安全的实例创建。
 *
 * @param args 构造器参数值包装
 * @param argTypes 构造器参数类型；为空时会根据 [args] 自动推断
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.instantiateAs(args: Args = args(), argTypes: ArgTypes = argTypes()): T =
    instantiate(args, argTypes) as T

/**
 * [instantiateAs] 的旧名字。
 */
@Deprecated(
    message = "改用 instantiateAs(...)",
    replaceWith = ReplaceWith("instantiateAs<T>(args, argTypes)"),
    level = DeprecationLevel.WARNING,
)
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.newInstanceAs(args: Args = args(), argTypes: ArgTypes = argTypes()): T =
    instantiate(args, argTypes) as T

/**
 * 自动匹配构造器参数类型。
 *
 * @param args 用于匹配构造器的运行时参数
 */
fun Class<*>.newInstanceAuto(vararg args: Any?): Any {
    return constructAutoMatchedInstance(this, args)
}

/**
 * 类型安全的自动匹配实例创建。
 *
 * @param args 用于匹配构造器的运行时参数
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.newInstanceAutoAs(vararg args: Any?): T =
    newInstanceAuto(*args) as T

/**
 * 按类名创建实例。
 *
 * @param className 目标类名
 * @param args 构造器参数值包装
 * @param argTypes 构造器参数类型；为空时会根据 [args] 自动推断
 * @param classLoader 用于加载目标类的 `ClassLoader`
 */
fun newInstance(
    className: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    classLoader: ClassLoader = EzReflect.classLoader,
): Any = loadClass(className, classLoader).instantiate(args, argTypes)

/**
 * 按类名创建实例（类型安全）。
 *
 * @param className 目标类名
 * @param args 构造器参数值包装
 * @param argTypes 构造器参数类型；为空时会根据 [args] 自动推断
 * @param classLoader 用于加载目标类的 `ClassLoader`
 */
@Suppress("UNCHECKED_CAST")
fun <T> newInstanceAs(
    className: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    classLoader: ClassLoader = EzReflect.classLoader,
): T = newInstance(className, args, argTypes, classLoader) as T

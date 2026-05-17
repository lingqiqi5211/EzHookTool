@file:JvmName("MethodUtils")

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import io.github.lingqiqi5211.ezhooktool.core.query.methodCondition
import io.github.lingqiqi5211.ezhooktool.core.query.methodExactCacheKeys
import io.github.lingqiqi5211.ezhooktool.core.query.methodQuery
import java.lang.reflect.Method

/**
 * 方法查找条件。以 Method 为 receiver，可直接访问 name、parameterTypes 等。
 *
 * ```kotlin
 * findMethod(clz) {
 *     name("doTask")
 *     paramCount(2)
 * }
 * ```
 */
typealias MethodCondition = Method.() -> Boolean

// ═══════════════════════ Internal: Smart Search ═══════════════════════

/**
 * 遍历类的继承链搜索方法。
 *
 * @param findSuper null=先当前后父类(找到即停), false=仅当前类, true=全继承链
 * @param collectAll true=收集所有匹配, false=找到第一个即返回
 */
private fun searchMethods(
    clz: Class<*>,
    findSuper: Boolean?,
    collectAll: Boolean,
    condition: MethodCondition,
): List<Method> {
    val results = mutableListOf<Method>()

    when (findSuper) {
        false -> {
            // 仅当前类
            for (m in EzReflect.memberResolver.methodsOf(clz)) {
                if (condition(m)) {
                    m.isAccessible = true
                    if (!collectAll) return listOf(m)
                    results.add(m.also { it.isAccessible = true })
                }
            }
        }

        true -> {
            // 强制搜索整个继承链
            var current: Class<*>? = clz
            while (current != null) {
                for (m in EzReflect.memberResolver.methodsOf(current)) {
                    if (condition(m)) {
                        m.isAccessible = true
                        if (!collectAll) return listOf(m)
                        results.add(m)
                    }
                }
                current = current.superclass
            }
        }

        null -> {
            // 智能搜索：先当前类，找不到自动向上
            var current: Class<*>? = clz
            while (current != null) {
                for (m in EzReflect.memberResolver.methodsOf(current)) {
                    if (condition(m)) {
                        m.isAccessible = true
                        if (!collectAll) return listOf(m)
                        results.add(m)
                    }
                }
                // 对于 collectAll=false (findMethod)，如果当前类没找到就继续向上
                // 对于 collectAll=true (findAllMethods, null mode)，只搜到找到为止
                current = if (!collectAll && results.isEmpty()) {
                    current.superclass
                } else if (collectAll && results.isNotEmpty()) {
                    // null mode + collectAll: 当前类有结果就停
                    return results
                } else if (collectAll) {
                    current.superclass
                } else {
                    break
                }
            }
        }
    }
    return results
}

/**
 * 生成候选列表（debugMode 用）。
 */
private fun getCandidates(clz: Class<*>): List<String> {
    if (!EzReflect.debugMode) return emptyList()
    return EzReflect.memberResolver.methodsOf(clz).map { it.toReadableString() }
}

// ═══════════════════════ Cache Key ═══════════════════════

private data class MethodCacheKey(
    val queryKey: List<Any>,
    val findSuper: Boolean?,
)

private data class MethodNameCacheKey(
    val name: String,
    val argTypes: List<Class<*>>,
    val returnType: Class<*>?,
)

private data class DeclaringMethodCacheKey(
    val owner: Class<*>,
    val key: MethodCacheKey,
)

private fun cacheExactMethods(
    searchClass: Class<*>,
    findSuper: Boolean?,
    methods: List<Method>,
    cacheSearchClass: Boolean,
) {
    if (!EzReflect.cacheEnabled) return
    val searchKeys = HashSet<MethodCacheKey>()
    val declaringKeys = HashSet<DeclaringMethodCacheKey>()

    for (method in methods) {
        for (queryKey in methodExactCacheKeys(method)) {
            if (cacheSearchClass) {
                val key = MethodCacheKey(queryKey, findSuper)
                if (searchKeys.add(key)) {
                    EzReflect.cachePut(searchClass, ReflectCacheBucket.METHOD, key, method)
                }
            }

            val currentClassKey = MethodCacheKey(queryKey, false)
            val smartKey = MethodCacheKey(queryKey, null)
            if (declaringKeys.add(DeclaringMethodCacheKey(method.declaringClass, currentClassKey))) {
                EzReflect.cachePut(method.declaringClass, ReflectCacheBucket.METHOD, currentClassKey, method)
            }
            if (declaringKeys.add(DeclaringMethodCacheKey(method.declaringClass, smartKey))) {
                EzReflect.cachePut(method.declaringClass, ReflectCacheBucket.METHOD, smartKey, method)
            }
        }
    }
}

// ═══════════════════════ 按条件查找 (Class) ═══════════════════════

/**
 * 按查询条件查找方法。
 *
 * 默认行为：先查当前类，找不到自动向上搜索父类。
 *
 * ```kotlin
 * val m = findMethod(clz) { name("doTask") }
 *
 * val m = findMethod(clz) {
 *     name("doTask")
 *     paramCount(2)
 *     params(String::class.java, Int::class.java)
 * }
 *
 * // 强制只查当前类
 * val m = findMethod(clz, findSuper = false) { name("doTask") }
 * ```
 *
 * @param clz        目标类
 * @param findSuper  null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param query      查询条件块
 * @return 第一个匹配的 Method（已 setAccessible）
 * @throws MemberNotFoundException 找不到时
 */
fun findMethod(
    clz: Class<*>,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method {
    val builtQuery = methodQuery(query)
    val effectiveFindSuper = builtQuery.effectiveFindSuper(findSuper)
    return findMethodOrNull(clz, findSuper, builtQuery)
        ?: throw MemberNotFoundException(
            memberType = MemberType.METHOD,
            targetClass = clz.name,
            searchedSuper = effectiveFindSuper != false,
            candidates = getCandidates(clz)
        )
}

/**
 * 按查询条件查找方法，找不到返回 null。
 */
fun findMethodOrNull(
    clz: Class<*>,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method? {
    return findMethodOrNull(clz, findSuper, methodQuery(query))
}

private fun findMethodOrNull(
    clz: Class<*>,
    findSuper: Boolean?,
    query: MethodQuery,
): Method? {
    val effectiveFindSuper = query.effectiveFindSuper(findSuper)
    val condition = methodCondition(query)
    val queryKey = query.cacheKeyOrNull()
    if (EzReflect.cacheEnabled && queryKey != null) {
        val key = MethodCacheKey(queryKey, effectiveFindSuper)
        val cached = EzReflect.cacheGet(clz, ReflectCacheBucket.METHOD, key)
        if (cached is Method) return cached
    }
    val result = searchMethods(clz, effectiveFindSuper, collectAll = false, condition).firstOrNull()
    if (result != null && EzReflect.cacheEnabled && queryKey != null) {
        EzReflect.cachePut(clz, ReflectCacheBucket.METHOD, MethodCacheKey(queryKey, effectiveFindSuper), result)
    }
    return result
}

/**
 * 按类名查找方法。
 *
 * ```kotlin
 * val m = findMethod("com.example.Target") { name("doTask") }
 * ```
 */
fun findMethod(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method = findMethod(loadClass(className, classLoader), findSuper, query)

/**
 * 按类名查找方法，找不到返回 null。
 */
fun findMethodOrNull(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method? {
    val clz = loadClassOrNull(className, classLoader) ?: return null
    return findMethodOrNull(clz, findSuper, query)
}

private fun findAllMethodsMatching(
    clz: Class<*>,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): List<Method> = searchMethods(clz, findSuper, collectAll = true, condition)

/**
 * 查找全部方法。
 */
fun findAllMethods(
    clz: Class<*>,
    findSuper: Boolean? = null,
): List<Method> = findAllMethodsMatching(clz, findSuper) { true }

/**
 * 按查询条件查找方法。
 */
fun findAllMethods(
    clz: Class<*>,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): List<Method> {
    val builtQuery = methodQuery(query)
    val effectiveFindSuper = builtQuery.effectiveFindSuper(findSuper)
    val cacheSearchClass = builtQuery.cacheKeyOrNull() != null
    findMethodOrNull(clz, findSuper, builtQuery) ?: return emptyList()
    val results = findAllMethodsMatching(
        clz = clz,
        findSuper = effectiveFindSuper,
        condition = methodCondition(builtQuery),
    )
    cacheExactMethods(clz, effectiveFindSuper, results, cacheSearchClass)
    return results
}

/**
 * 按类名查找全部方法。
 */
fun findAllMethods(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
): List<Method> = findAllMethods(loadClass(className, classLoader), findSuper)

/**
 * 按类名和查询条件查找方法。
 */
fun findAllMethods(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): List<Method> = findAllMethods(loadClass(className, classLoader), findSuper, query)

// ═══════════════════════ 按名称直接获取 ═══════════════════════

/**
 * 按名称获取方法（精确匹配参数类型）。
 *
 * ```kotlin
 * val m = clz.method("doTask", argTypes(String::class.java, Int::class.java))
 * ```
 */
fun Class<*>.method(
    name: String,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Method {
    return methodOrNull(name, argTypes, returnType)
        ?: throw MemberNotFoundException(
            memberType = MemberType.METHOD,
            targetClass = this.name,
            searchedSuper = false,
            conditionDesc = "name=$name, argTypes=${argTypes.types.map { it.simpleName }}" +
                    (if (returnType != null) ", returnType=${returnType.simpleName}" else ""),
            candidates = getCandidates(this)
        )
}

/**
 * 按名称获取方法，找不到返回 null。
 */
fun Class<*>.methodOrNull(
    name: String,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Method? {
    if (EzReflect.cacheEnabled) {
        val key = MethodNameCacheKey(name, argTypes.types.toList(), returnType)
        val cached = EzReflect.cacheGet(this, ReflectCacheBucket.METHOD, key)
        if (cached is Method) return cached
    }
    val method = try {
        val m = getDeclaredMethod(name, *argTypes.types)
        if (returnType != null && m.returnType != returnType) null
        else {
            m.isAccessible = true
            m
        }
    } catch (_: NoSuchMethodException) {
        // Fallback: search superclasses
        var current: Class<*>? = superclass
        var found: Method? = null
        while (current != null && found == null) {
            found = try {
                val m = current.getDeclaredMethod(name, *argTypes.types)
                if (returnType != null && m.returnType != returnType) null
                else {
                    m.isAccessible = true
                    m
                }
            } catch (_: NoSuchMethodException) {
                null
            }
            current = current.superclass
        }
        found
    }
    if (method != null && EzReflect.cacheEnabled) {
        EzReflect.cachePut(
            this,
            ReflectCacheBucket.METHOD,
            MethodNameCacheKey(name, argTypes.types.toList(), returnType),
            method,
        )
    }
    return method
}

// ═══════════════════════ 组合态链式 (String 出发) ═══════════════════════

/**
 * 从类名直接查找方法的便捷写法，等价于 `loadClass(name).findMethod { ... }`。
 * 适合一次性查找场景；多次操作同一个类时推荐先显式 loadClass / loadClassFirst。
 *
 * ```kotlin
 * val m = "com.example.Target".findMethod { name("doTask") }
 * ```
 */
@JvmName("findMethodFromString")
fun String.findMethod(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method = findMethod(loadClass(this, classLoader), findSuper, query)

/**
 * 从类名直接查找方法，找不到返回 null。
 */
@JvmName("findMethodOrNullFromString")
fun String.findMethodOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method? {
    val clz = loadClassOrNull(this, classLoader) ?: return null
    return findMethodOrNull(clz, findSuper, query)
}

/**
 * 从类名查找全部方法。
 */
@JvmName("findAllMethodsFromString")
fun String.findAllMethods(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
): List<Method> = findAllMethods(loadClass(this, classLoader), findSuper)

/**
 * 从类名按查询条件查找方法。
 */
@JvmName("findAllMethodsFromStringWithQuery")
fun String.findAllMethods(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): List<Method> = findAllMethods(loadClass(this, classLoader), findSuper, query)

// ═══════════════════════ 组合态链式 (Class 出发) ═══════════════════════

/**
 * 从 Class 对象直接查找方法。
 *
 * ```kotlin
 * val m = clz.findMethod {
 *     name("doTask")
 *     filter { isPublic }
 * }
 * ```
 */
@JvmName("findMethodFromClass")
fun Class<*>.findMethod(
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method = findMethod(this, findSuper, query)

/**
 * 从 Class 对象查找方法，找不到返回 null。
 */
@JvmName("findMethodOrNullFromClass")
fun Class<*>.findMethodOrNull(
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): Method? = findMethodOrNull(this, findSuper, query)

/**
 * 从 Class 对象查找全部方法。
 */
@JvmName("findAllMethodsFromClass")
fun Class<*>.findAllMethods(
    findSuper: Boolean? = null,
): List<Method> = findAllMethods(this, findSuper)

/**
 * 从 Class 对象按查询条件查找方法。
 */
@JvmName("findAllMethodsFromClassWithQuery")
fun Class<*>.findAllMethods(
    findSuper: Boolean? = null,
    query: MethodQuery.() -> Unit,
): List<Method> = findAllMethods(this, findSuper, query)

// ═══════════════════════ 实例方法调用 ═══════════════════════

/**
 * 按名称调用实例方法。
 *
 * ```kotlin
 * val result = instance.callMethod("doTask", args("hello", 42))
 * ```
 */
fun Any.callMethod(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? {
    val types = if (argTypes.types.isNotEmpty()) argTypes else argTypes(*inferArgTypes(args.args))
    val method = javaClass.method(methodName, types, returnType)
    return method.invoke(this, *args.args)
}

/**
 * 按名称调用实例方法，返回可空结果。
 */
fun Any.callMethodOrNull(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? = callMethod(methodName, args, argTypes, returnType)

/**
 * 类型安全的方法调用。
 *
 * ```kotlin
 * val name: String = instance.callMethodAs("getName")
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.callMethodAs(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): T = callMethod(methodName, args, argTypes, returnType) as T

/**
 * 类型安全的方法调用，类型不匹配时返回 null。
 */
inline fun <reified T> Any.callMethodAsOrNull(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): T? = callMethod(methodName, args, argTypes, returnType) as? T

/**
 * 自动匹配参数并调用实例方法。
 *
 * ```kotlin
 * val result = instance.callMethod("doTask", "hello", 42)
 * val name = instance.callMethodAsOrNull<String>("getName")
 * ```
 */
fun Any.callMethod(methodName: String, vararg args: Any?): Any? =
    invokeAutoMatchedMethod(javaClass, this, methodName, args)

/**
 * 自动匹配参数并调用实例方法，返回可空结果。
 */
fun Any.callMethodOrNull(methodName: String, vararg args: Any?): Any? =
    callMethod(methodName, *args)

/**
 * 自动匹配参数并调用实例方法。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.callMethodAs(methodName: String, vararg args: Any?): T =
    callMethod(methodName, *args) as T

/**
 * 自动匹配参数并调用实例方法，类型不匹配时返回 null。
 */
inline fun <reified T> Any.callMethodAsOrNull(methodName: String, vararg args: Any?): T? =
    callMethod(methodName, *args) as? T

/**
 * 按查询条件查找并调用。
 *
 * ```kotlin
 * val result = instance.callMethodBy(args = arrayOf("hello")) {
 *     name("doTask")
 *     paramCount(1)
 * }
 * ```
 */
fun Any.callMethodBy(
    args: Array<out Any?> = emptyArray(),
    query: MethodQuery.() -> Unit,
): Any? {
    val method = findMethod(javaClass, query = query)
    return method.invoke(this, *args)
}

/**
 * 按条件查找并调用。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.callMethodByAs(
    args: Array<out Any?> = emptyArray(),
    query: MethodQuery.() -> Unit,
): T = callMethodBy(args, query) as T

// ═══════════════════════ 静态方法调用 ═══════════════════════

/**
 * 调用静态方法。
 *
 * ```kotlin
 * val result = targetClass.callStaticMethod("getInstance", args())
 * ```
 */
fun Class<*>.callStaticMethod(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? {
    val types = if (argTypes.types.isNotEmpty()) argTypes else argTypes(*inferArgTypes(args.args))
    val method = method(methodName, types, returnType)
    return method.invoke(null, *args.args)
}

/**
 * 调用静态方法，返回可空结果。
 */
fun Class<*>.callStaticMethodOrNull(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? = callStaticMethod(methodName, args, argTypes, returnType)

/**
 * 静态方法调用。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.callStaticMethodAs(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): T = callStaticMethod(methodName, args, argTypes, returnType) as T

/**
 * 静态方法调用，类型不匹配时返回 null。
 */
inline fun <reified T> Class<*>.callStaticMethodAsOrNull(
    methodName: String,
    args: Args,
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): T? = callStaticMethod(methodName, args, argTypes, returnType) as? T

/**
 * 自动匹配参数并调用静态方法。
 *
 * ```kotlin
 * val result = targetClass.callStaticMethod("getInstance")
 * ```
 */
fun Class<*>.callStaticMethod(methodName: String, vararg args: Any?): Any? =
    invokeAutoMatchedMethod(this, null, methodName, args)

/**
 * 自动匹配参数并调用静态方法，返回可空结果。
 */
fun Class<*>.callStaticMethodOrNull(methodName: String, vararg args: Any?): Any? =
    callStaticMethod(methodName, *args)

/**
 * 自动匹配参数并调用静态方法。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.callStaticMethodAs(methodName: String, vararg args: Any?): T =
    callStaticMethod(methodName, *args) as T

/**
 * 自动匹配参数并调用静态方法，类型不匹配时返回 null。
 */
inline fun <reified T> Class<*>.callStaticMethodAsOrNull(methodName: String, vararg args: Any?): T? =
    callStaticMethod(methodName, *args) as? T

// ═══════════════════════ Method 扩展 ═══════════════════════

/**
 * 类型安全的 Method.invoke 包装。
 *
 * ```kotlin
 * val result: String = method.invokeAs(instance, "arg1")
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <T> Method.invokeAs(obj: Any?, vararg args: Any?): T? {
    isAccessible = true
    return invoke(obj, *args) as T?
}

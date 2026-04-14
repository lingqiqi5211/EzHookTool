@file:JvmName("MethodUtils")

package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Method

/**
 * 方法查找条件。以 Method 为 receiver，可直接访问 name、parameterTypes 等。
 *
 * ```kotlin
 * findMethod(clz) {
 *     name == "doTask" && isPublic && paramCount == 2
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
                if (!collectAll && results.isEmpty()) {
                    current = current.superclass
                } else if (collectAll && results.isNotEmpty()) {
                    // null mode + collectAll: 当前类有结果就停
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

/**
 * 生成候选列表（debugMode 用）。
 */
private fun getCandidates(clz: Class<*>): List<String> {
    if (!EzReflect.debugMode) return emptyList()
    return EzReflect.memberResolver.methodsOf(clz).map { it.toReadableString() }
}

// ═══════════════════════ Cache Key ═══════════════════════

private data class MethodCacheKey(
    val clz: Class<*>,
    val conditionIdentity: Any,
    val findSuper: Boolean?,
)

private data class MethodNameCacheKey(
    val clz: Class<*>,
    val name: String,
    val argTypes: List<Class<*>>,
    val returnType: Class<*>?,
)

// ═══════════════════════ 按条件查找 (Class) ═══════════════════════

/**
 * 按条件查找方法。
 *
 * 默认行为：先查当前类，找不到自动向上搜索父类。
 *
 * ```kotlin
 * val m = findMethod(clz) { name == "doTask" }
 *
 * val m = findMethod(clz) {
 *     name == "doTask" && isPublic && paramCount == 2
 *     && parameterTypes[0] == String::class.java
 * }
 *
 * // 强制只查当前类
 * val m = findMethod(clz, findSuper = false) { name == "doTask" }
 * ```
 *
 * @param clz        目标类
 * @param findSuper  null=智能搜索(默认), false=仅当前类, true=强制搜索继承链
 * @param condition  条件 lambda，receiver 为 Method
 * @return 第一个匹配的 Method（已 setAccessible）
 * @throws MemberNotFoundException 找不到时
 */
fun findMethod(
    clz: Class<*>,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method {
    return findMethodOrNull(clz, findSuper, condition)
        ?: throw MemberNotFoundException(
            memberType = MemberType.METHOD,
            targetClass = clz.name,
            searchedSuper = findSuper != false,
            candidates = getCandidates(clz)
        )
}

/**
 * 按条件查找方法，找不到返回 null。
 */
fun findMethodOrNull(
    clz: Class<*>,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method? {
    if (EzReflect.cacheEnabled) {
        val key = MethodCacheKey(clz, condition, findSuper)
        val cached = EzReflect.cache[key]
        if (cached is Method) return cached
    }
    val result = searchMethods(clz, findSuper, collectAll = false, condition).firstOrNull()
    if (result != null && EzReflect.cacheEnabled) {
        EzReflect.cache[MethodCacheKey(clz, condition, findSuper)] = result
    }
    return result
}

/**
 * 按类名查找方法。
 *
 * ```kotlin
 * val m = findMethod("com.example.Target") { name == "doTask" }
 * ```
 */
fun findMethod(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method = findMethod(loadClass(className, classLoader), findSuper, condition)

/**
 * 按类名查找方法，找不到返回 null。
 */
fun findMethodOrNull(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method? {
    val clz = loadClassOrNull(className, classLoader) ?: return null
    return findMethodOrNull(clz, findSuper, condition)
}

/**
 * 查找所有匹配的方法。
 *
 * ```kotlin
 * val methods = findAllMethods(clz) { isPublic && returnType == Void.TYPE }
 * ```
 */
fun findAllMethods(
    clz: Class<*>,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): List<Method> = searchMethods(clz, findSuper, collectAll = true, condition)

/**
 * 按类名查找所有匹配的方法。
 */
fun findAllMethods(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): List<Method> = findAllMethods(loadClass(className, classLoader), findSuper, condition)

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
        val key = MethodNameCacheKey(this, name, argTypes.types.toList(), returnType)
        val cached = EzReflect.cache[key]
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
        EzReflect.cache[MethodNameCacheKey(this, name, argTypes.types.toList(), returnType)] = method
    }
    return method
}

// ═══════════════════════ 组合态链式 (String 出发) ═══════════════════════

/**
 * 从类名直接查找方法的便捷写法，等价于 `loadClass(name).findMethod { ... }`。
 * 适合一次性查找场景；多次操作同一个类时推荐先显式 loadClass / loadClassFirst。
 *
 * ```kotlin
 * val m = "com.example.Target".findMethod { name == "doTask" }
 * ```
 */
@JvmName("findMethodByString")
fun String.findMethod(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method = findMethod(loadClass(this, classLoader), findSuper, condition)

/**
 * 从类名直接查找方法，找不到返回 null。
 */
@JvmName("findMethodOrNullByString")
fun String.findMethodOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method? {
    val clz = loadClassOrNull(this, classLoader) ?: return null
    return findMethodOrNull(clz, findSuper, condition)
}

/**
 * 从类名查找所有匹配的方法。
 */
@JvmName("findAllMethodsByString")
fun String.findAllMethods(
    classLoader: ClassLoader = EzReflect.classLoader,
    findSuper: Boolean? = null,
    condition: MethodCondition,
): List<Method> = findAllMethods(loadClass(this, classLoader), findSuper, condition)

// ═══════════════════════ 组合态链式 (Class 出发) ═══════════════════════

/**
 * 从 Class 对象直接查找方法。
 *
 * ```kotlin
 * val m = clz.findMethod { name == "doTask" && isPublic }
 * ```
 */
@JvmName("findMethodByClass")
fun Class<*>.findMethod(
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method = findMethod(this, findSuper, condition)

/**
 * 从 Class 对象查找方法，找不到返回 null。
 */
@JvmName("findMethodOrNullByClass")
fun Class<*>.findMethodOrNull(
    findSuper: Boolean? = null,
    condition: MethodCondition,
): Method? = findMethodOrNull(this, findSuper, condition)

/**
 * 从 Class 对象查找所有匹配的方法。
 */
@JvmName("findAllMethodsByClass")
fun Class<*>.findAllMethods(
    findSuper: Boolean? = null,
    condition: MethodCondition,
): List<Method> = findAllMethods(this, findSuper, condition)

// ═══════════════════════ 实例方法调用 ═══════════════════════

/**
 * 按名称调用实例方法。
 *
 * ```kotlin
 * val result = instance.invokeMethod("doTask", args("hello", 42))
 * ```
 */
fun Any.invokeMethod(
    methodName: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? {
    val types = if (argTypes.types.isNotEmpty()) argTypes else argTypes(*inferArgTypes(args.args))
    val method = javaClass.method(methodName, types, returnType)
    return method.invoke(this, *args.args)
}

/**
 * 类型安全的方法调用。
 *
 * ```kotlin
 * val name: String = instance.invokeMethodAs("getName")
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodAs(
    methodName: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): T? = invokeMethod(methodName, args, argTypes, returnType) as T?

/**
 * 自动匹配参数类型的方法调用。
 *
 * ```kotlin
 * val result = instance.invokeMethodAuto("doTask", "hello", 42)
 * ```
 */
fun Any.invokeMethodAuto(methodName: String, vararg args: Any?): Any? {
    return invokeAutoMatchedMethod(javaClass, this, methodName, args)
}

/**
 * 类型安全的自动匹配调用。
 *
 * ```kotlin
 * val name: String = instance.invokeMethodAutoAs("getName")
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodAutoAs(methodName: String, vararg args: Any?): T? =
    invokeMethodAuto(methodName, *args) as T?

/**
 * 按条件查找并调用。
 *
 * ```kotlin
 * val result = instance.invokeMethodBy(args = arrayOf("hello")) {
 *     name == "doTask" && paramCount == 1
 * }
 * ```
 */
fun Any.invokeMethodBy(
    args: Array<out Any?> = emptyArray(),
    condition: MethodCondition,
): Any? {
    val method = findMethod(javaClass, condition = condition)
    return method.invoke(this, *args)
}

/**
 * 按条件查找并调用（类型安全）。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodByAs(
    args: Array<out Any?> = emptyArray(),
    condition: MethodCondition,
): T? = invokeMethodBy(args, condition) as T?

// ═══════════════════════ 静态方法调用 ═══════════════════════

/**
 * 调用静态方法。
 *
 * ```kotlin
 * val result = targetClass.invokeStaticMethod("getInstance")
 * ```
 */
fun Class<*>.invokeStaticMethod(
    methodName: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? {
    val types = if (argTypes.types.isNotEmpty()) argTypes else argTypes(*inferArgTypes(args.args))
    val method = method(methodName, types, returnType)
    return method.invoke(null, *args.args)
}

/**
 * 类型安全的静态方法调用。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.invokeStaticMethodAs(
    methodName: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): T? = invokeStaticMethod(methodName, args, argTypes, returnType) as T?

/**
 * 自动匹配参数类型的静态方法调用。
 */
fun Class<*>.invokeStaticMethodAuto(methodName: String, vararg args: Any?): Any? {
    return invokeAutoMatchedMethod(this, null, methodName, args)
}

/**
 * 类型安全的自动匹配静态方法调用。
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.invokeStaticMethodAutoAs(methodName: String, vararg args: Any?): T? =
    invokeStaticMethodAuto(methodName, *args) as T?

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

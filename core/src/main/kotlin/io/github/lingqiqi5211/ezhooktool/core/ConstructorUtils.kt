@file:JvmName("ConstructorUtils")

package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Constructor

/**
 * 构造器查找条件。以 Constructor 为 receiver。
 *
 * ```kotlin
 * findConstructor(clz) { hasEmptyParam }
 * findConstructor(clz) { paramCount == 2 && parameterTypes[0] == String::class.java }
 * ```
 */
typealias ConstructorCondition = Constructor<*>.() -> Boolean

// ═══════════════════════ Internal ═══════════════════════

private fun getConstructorCandidates(clz: Class<*>): List<String> {
    if (!EzReflect.debugMode) return emptyList()
    return EzReflect.memberResolver.constructorsOf(clz).map { it.toReadableString() }
}

private data class ConstructorCacheKey(
    val clz: Class<*>,
    val conditionIdentity: Any,
)

// ═══════════════════════ 按条件查找 ═══════════════════════

/**
 * 按条件查找构造器。
 *
 * ```kotlin
 * val ctor = findConstructor(clz) { hasEmptyParam }
 * val ctor = findConstructor(clz) { paramCount == 2 && parameterTypes[0] == String::class.java }
 * ```
 *
 * @param clz       目标类
 * @param condition 条件 lambda，receiver 为 Constructor
 * @return 第一个匹配的 Constructor（已 setAccessible）
 * @throws MemberNotFoundException 找不到时
 */
fun findConstructor(clz: Class<*>, condition: ConstructorCondition): Constructor<*> {
    return findConstructorOrNull(clz, condition)
        ?: throw MemberNotFoundException(
            memberType = MemberType.CONSTRUCTOR,
            targetClass = clz.name,
            searchedSuper = false,
            candidates = getConstructorCandidates(clz)
        )
}

/**
 * 按条件查找构造器，找不到返回 null。
 *
 * @param clz 目标类
 * @param condition 条件 lambda，receiver 为 Constructor
 */
fun findConstructorOrNull(clz: Class<*>, condition: ConstructorCondition): Constructor<*>? {
    if (EzReflect.cacheEnabled) {
        val key = ConstructorCacheKey(clz, condition)
        val cached = EzReflect.cache[key]
        if (cached is Constructor<*>) return cached
    }
    for (ctor in EzReflect.memberResolver.constructorsOf(clz)) {
        if (condition(ctor)) {
            ctor.isAccessible = true
            if (EzReflect.cacheEnabled) {
                EzReflect.cache[ConstructorCacheKey(clz, condition)] = ctor
            }
            return ctor
        }
    }
    return null
}

/**
 * 按类名查找构造器。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param condition 条件 lambda，receiver 为 Constructor
 */
fun findConstructor(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    condition: ConstructorCondition,
): Constructor<*> = findConstructor(loadClass(className, classLoader), condition)

/**
 * 按类名查找构造器，找不到返回 null。
 *
 * @param className 目标类名
 * @param classLoader 用于加载目标类的 `ClassLoader`
 * @param condition 条件 lambda，receiver 为 Constructor
 */
fun findConstructorOrNull(
    className: String,
    classLoader: ClassLoader = EzReflect.classLoader,
    condition: ConstructorCondition,
): Constructor<*>? {
    val clz = loadClassOrNull(className, classLoader) ?: return null
    return findConstructorOrNull(clz, condition)
}

/**
 * 查找所有匹配的构造器。
 *
 * @param clz 目标类
 * @param condition 条件 lambda，receiver 为 Constructor
 */
fun findAllConstructors(clz: Class<*>, condition: ConstructorCondition): List<Constructor<*>> {
    val results = mutableListOf<Constructor<*>>()
    for (ctor in EzReflect.memberResolver.constructorsOf(clz)) {
        if (condition(ctor)) {
            ctor.isAccessible = true
            results.add(ctor)
        }
    }
    return results
}

// ═══════════════════════ 组合态链式 (String 出发) ═══════════════════════

/**
 * 从类名直接查找构造器的便捷写法，等价于 `loadClass(name).findConstructor { ... }`。
 * 适合一次性查找场景；多次操作同一个类时推荐先显式 loadClass / loadClassFirst。
 *
 * ```kotlin
 * val ctor = "com.example.Target".findConstructor { hasEmptyParam }
 * ```
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 * @param condition 条件 lambda，receiver 为 Constructor
 */
@JvmName("findConstructorByString")
fun String.findConstructor(
    classLoader: ClassLoader = EzReflect.classLoader,
    condition: ConstructorCondition,
): Constructor<*> = findConstructor(loadClass(this, classLoader), condition)

/**
 * 从类名查找构造器，找不到返回 null。
 *
 * @param classLoader 用于加载当前类名的 `ClassLoader`
 * @param condition 条件 lambda，receiver 为 Constructor
 */
@JvmName("findConstructorOrNullByString")
fun String.findConstructorOrNull(
    classLoader: ClassLoader = EzReflect.classLoader,
    condition: ConstructorCondition,
): Constructor<*>? {
    val clz = loadClassOrNull(this, classLoader) ?: return null
    return findConstructorOrNull(clz, condition)
}

// ═══════════════════════ 组合态链式 (Class 出发) ═══════════════════════

/**
 * 从 Class 对象直接查找构造器。
 *
 * ```kotlin
 * val ctor = clz.findConstructor { paramCount == 2 }
 * ```
 *
 * @param condition 条件 lambda，receiver 为 Constructor
 */
@JvmName("findConstructorByClass")
fun Class<*>.findConstructor(condition: ConstructorCondition): Constructor<*> =
    findConstructor(this, condition)

/**
 * 从 Class 对象查找构造器，找不到返回 null。
 *
 * @param condition 条件 lambda，receiver 为 Constructor
 */
@JvmName("findConstructorOrNullByClass")
fun Class<*>.findConstructorOrNull(condition: ConstructorCondition): Constructor<*>? =
    findConstructorOrNull(this, condition)

/**
 * 从 Class 对象查找所有匹配的构造器。
 *
 * @param condition 条件 lambda，receiver 为 Constructor
 */
@JvmName("findAllConstructorsByClass")
fun Class<*>.findAllConstructors(condition: ConstructorCondition): List<Constructor<*>> =
    findAllConstructors(this, condition)

// ═══════════════════════ 创建实例 ═══════════════════════

/**
 * 创建实例（精确参数类型）。
 *
 * ```kotlin
 * val obj = clz.newInstance()
 * val obj = clz.newInstance(args("param1", 42), argTypes(String::class.java, Int::class.java))
 * ```
 *
 * @param args 构造器参数值包装
 * @param argTypes 构造器参数类型；为空时会根据 [args] 自动推断
 */
fun Class<*>.newInstance(args: Args = args(), argTypes: ArgTypes = argTypes()): Any {
    val types = if (argTypes.types.isNotEmpty()) argTypes.types
    else if (args.args.isEmpty()) emptyArray()
    else inferArgTypes(args.args)
    val ctor = getDeclaredConstructor(*types)
    ctor.isAccessible = true
    return ctor.newInstance(*args.args)
}

/**
 * 类型安全的实例创建。
 *
 * ```kotlin
 * val config: Config = configClass.newInstanceAs()
 * ```
 *
 * @param args 构造器参数值包装
 * @param argTypes 构造器参数类型；为空时会根据 [args] 自动推断
 */
@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.newInstanceAs(args: Args = args(), argTypes: ArgTypes = argTypes()): T =
    newInstance(args, argTypes) as T

/**
 * 自动匹配构造器参数类型。
 *
 * ```kotlin
 * val obj = clz.newInstanceAuto("hello", 42)
 * ```
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
 * ```kotlin
 * val obj = newInstance("com.example.Config")
 * val obj = newInstance("com.example.Config", args("param"))
 * ```
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
): Any = loadClass(className, classLoader).newInstance(args, argTypes)

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

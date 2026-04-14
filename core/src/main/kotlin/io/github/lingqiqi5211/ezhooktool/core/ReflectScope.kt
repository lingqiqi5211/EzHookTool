@file:JvmName("ReflectScopeUtils")

package io.github.lingqiqi5211.ezhooktool.core

/** 标记 EzReflect DSL 作用域，避免不同 DSL receiver 混用。 */
@DslMarker
annotation class EzReflectScopeMarker

/**
 * 绑定指定 [classLoader] 的反射 DSL 作用域。
 *
 * 适合在局部代码块中集中使用 `loadClass`、`findMethod` 等 API，避免大量顶层函数直接暴露在当前作用域。
 *
 * @param classLoader 当前作用域内所有查找 API 默认使用的 `ClassLoader`
 */
@EzReflectScopeMarker
class ReflectScope internal constructor(
    /** 当前作用域绑定的默认 `ClassLoader`。 */
    val classLoader: ClassLoader,
) {
    /** 在当前作用域的 [classLoader] 上加载类，找不到时抛异常。 */
    fun loadClass(name: String): Class<*> = loadClass(name, classLoader)

    /** 在当前作用域的 [classLoader] 上加载类，找不到时返回 `null`。 */
    fun loadClassOrNull(name: String): Class<*>? = loadClassOrNull(name, classLoader)

    /** 按多个候选类名依次尝试加载，返回第一个成功的类。 */
    fun loadClassFirst(vararg names: String): Class<*> =
        loadClassFirst(*names, classLoader = classLoader)

    /** 按多个候选类名依次尝试加载，全部失败时返回 `null`。 */
    fun loadClassFirstOrNull(vararg names: String): Class<*>? =
        loadClassFirstOrNull(*names, classLoader = classLoader)

    /**
     * 在当前作用域的 [classLoader] 上按类名创建实例。
     *
     * @param className 目标类名
     * @param args 构造器实参数组
     * @param argTypes 构造器参数类型；留空时会根据 [args] 自动推断
     */
    fun newInstance(className: String, args: Args = args(), argTypes: ArgTypes = argTypes()): Any =
        newInstance(className, args, argTypes, classLoader)

    /** 通过 Dex/Smali 签名解析并获取方法。 */
    fun getMethodByDesc(desc: String): java.lang.reflect.Method =
        getMethodByDesc(desc, classLoader)

    /** 通过 Dex/Smali 签名解析并获取方法，找不到返回 `null`。 */
    fun getMethodByDescOrNull(desc: String): java.lang.reflect.Method? =
        getMethodByDescOrNull(desc, classLoader)

    /** 通过 Dex/Smali 签名解析并获取字段。 */
    fun getFieldByDesc(desc: String): java.lang.reflect.Field =
        getFieldByDesc(desc, classLoader)

    /** 通过 Dex/Smali 签名解析并获取字段，找不到返回 `null`。 */
    fun getFieldByDescOrNull(desc: String): java.lang.reflect.Field? =
        getFieldByDescOrNull(desc, classLoader)

    /** 在当前作用域的 [classLoader] 上把类名字符串解析为 [Class]。 */
    fun String.toClass(): Class<*> = loadClass(this)

    /** 在当前作用域的 [classLoader] 上把类名字符串解析为 [Class]，失败返回 `null`。 */
    fun String.toClassOrNull(): Class<*>? = loadClassOrNull(this)

    /** 在当前作用域的 [classLoader] 上按条件查找方法。 */
    fun String.findMethod(findSuper: Boolean? = null, condition: MethodCondition): java.lang.reflect.Method =
        findMethod(this, classLoader, findSuper, condition)

    /** 在当前作用域的 [classLoader] 上按条件查找方法，找不到返回 `null`。 */
    fun String.findMethodOrNull(findSuper: Boolean? = null, condition: MethodCondition): java.lang.reflect.Method? =
        findMethodOrNull(this, classLoader, findSuper, condition)

    /** 在当前作用域的 [classLoader] 上查找所有匹配的方法。 */
    fun String.findAllMethods(findSuper: Boolean? = null, condition: MethodCondition): List<java.lang.reflect.Method> =
        findAllMethods(this, classLoader, findSuper, condition)

    /** 在当前作用域的 [classLoader] 上按条件查找字段。 */
    fun String.findField(findSuper: Boolean? = null, condition: FieldCondition): java.lang.reflect.Field =
        findField(this, classLoader, findSuper, condition)

    /** 在当前作用域的 [classLoader] 上按条件查找字段，找不到返回 `null`。 */
    fun String.findFieldOrNull(findSuper: Boolean? = null, condition: FieldCondition): java.lang.reflect.Field? =
        findFieldOrNull(this, classLoader, findSuper, condition)

    /** 在当前作用域的 [classLoader] 上查找所有匹配的字段。 */
    fun String.findAllFields(findSuper: Boolean? = null, condition: FieldCondition): List<java.lang.reflect.Field> =
        findAllFields(this, classLoader, findSuper, condition)

    /** 在当前作用域的 [classLoader] 上按条件查找构造器。 */
    fun String.findConstructor(condition: ConstructorCondition): java.lang.reflect.Constructor<*> =
        findConstructor(this, classLoader, condition)

    /** 在当前作用域的 [classLoader] 上按条件查找构造器，找不到返回 `null`。 */
    fun String.findConstructorOrNull(condition: ConstructorCondition): java.lang.reflect.Constructor<*>? =
        findConstructorOrNull(this, classLoader, condition)
}

/**
 * 使用指定 [classLoader] 打开一个局部反射作用域。
 *
 * ```kotlin
 * reflect {
 *     val method = "com.example.Target".findMethod { name == "run" }
 * }
 * ```
 *
 * @param classLoader 作用域内默认使用的 `ClassLoader`
 * @param block 在 [ReflectScope] 上执行的 DSL 代码块
 */
fun <T> reflect(
    classLoader: ClassLoader = EzReflect.classLoader,
    block: ReflectScope.() -> T,
): T = ReflectScope(classLoader).block()

/** 使用当前 [ClassLoader] 作为默认加载器打开反射作用域。 */
@JvmName("reflectByClassLoader")
fun <T> ClassLoader.reflect(block: ReflectScope.() -> T): T = ReflectScope(this).block()

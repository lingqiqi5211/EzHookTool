@file:JvmName("Utils")

package io.github.lingqiqi5211.ezhooktool.core

import java.lang.reflect.Field
import java.lang.reflect.Method

@PublishedApi
internal const val EZHOOKTOOL_TAG = "EzHookTool"

/**
 * 执行代码块，异常时返回 null。
 *
 * ```kotlin
 * val result = tryOrNull { riskyOperation() }
 * ```
 */
inline fun <T> tryOrNull(block: () -> T?): T? = try {
    block()
} catch (_: Throwable) {
    null
}

/**
 * 执行代码块，异常时返回 false。
 *
 * ```kotlin
 * val success = tryOrFalse { riskyOperation(); true }
 * ```
 */
inline fun tryOrFalse(block: () -> Boolean): Boolean = try {
    block()
} catch (_: Throwable) {
    false
}

/**
 * 执行代码块，异常时通过 [EzReflect.logger] 打日志。
 *
 * ```kotlin
 * tryOrLog { riskyOperation() }
 * tryOrLog("MyTag") { riskyOperation() }
 * ```
 */
inline fun tryOrLog(tag: String = EZHOOKTOOL_TAG, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        EzReflect.logger.error(tag, "Exception caught: ${t.message}", t)
    }
}

/**
 * 执行代码块，异常时打日志并返回 null。
 *
 * ```kotlin
 * val result = tryOrLogNull { riskyOperation() }
 * ```
 */
inline fun <T> tryOrLogNull(tag: String = EZHOOKTOOL_TAG, block: () -> T?): T? = try {
    block()
} catch (t: Throwable) {
    EzReflect.logger.error(tag, "Exception caught: ${t.message}", t)
    null
}

/**
 * Primitive 类型到 Wrapper 类型的映射。
 */
private val PRIMITIVE_TO_WRAPPER: Map<Class<*>, Class<*>> = mapOf(
    Boolean::class.javaPrimitiveType!! to Boolean::class.javaObjectType,
    Byte::class.javaPrimitiveType!! to Byte::class.javaObjectType,
    Char::class.javaPrimitiveType!! to Char::class.javaObjectType,
    Short::class.javaPrimitiveType!! to Short::class.javaObjectType,
    Int::class.javaPrimitiveType!! to Int::class.javaObjectType,
    Long::class.javaPrimitiveType!! to Long::class.javaObjectType,
    Float::class.javaPrimitiveType!! to Float::class.javaObjectType,
    Double::class.javaPrimitiveType!! to Double::class.javaObjectType,
    Void::class.javaPrimitiveType!! to Void::class.javaObjectType,
)

/**
 * 判断两个参数类型数组是否匹配（支持 primitive/wrapper 自动匹配）。
 *
 * ```kotlin
 * val match = paramTypesMatch(
 *     actual = arrayOf(Int::class.java, String::class.java),
 *     expected = arrayOf(JInteger::class.java, JString::class.java)
 * ) // true
 * ```
 */
fun paramTypesMatch(actual: Array<Class<*>>, expected: Array<Class<*>>): Boolean {
    if (actual.size != expected.size) return false
    for (i in actual.indices) {
        if (!isTypeMatch(actual[i], expected[i])) return false
    }
    return true
}

/**
 * 判断两个类型是否匹配（考虑 primitive/wrapper 互转和继承关系）。
 */
internal fun isTypeMatch(actual: Class<*>, expected: Class<*>): Boolean {
    if (actual == expected) return true
    // primitive <-> wrapper
    val actualWrapped = PRIMITIVE_TO_WRAPPER[actual] ?: actual
    val expectedWrapped = PRIMITIVE_TO_WRAPPER[expected] ?: expected
    if (actualWrapped == expectedWrapped) return true
    // 继承关系
    return expected.isAssignableFrom(actual)
}

/**
 * 根据实参推断参数类型数组。
 */
internal fun inferArgTypes(args: Array<out Any?>): Array<Class<*>> =
    Array(args.size) { i ->
        args[i]?.javaClass ?: Any::class.java
    }

@PublishedApi
internal fun Any.ownerClass(): Class<*> = if (this is Class<*>) this else javaClass

@PublishedApi
internal fun findFirstFieldByType(owner: Any, type: Class<*>, isStatic: Boolean): Field? {
    val clz = owner.ownerClass()
    return findFieldOrNull(clz) {
        this.type == type && this.isStatic == isStatic
    }
}

@PublishedApi
internal fun readFieldValue(field: Field, receiver: Any?): Any? {
    field.isAccessible = true
    return field.get(receiver)
}

@PublishedApi
internal fun writeFieldValue(field: Field, receiver: Any?, value: Any?) {
    field.isAccessible = true
    field.set(receiver, value)
}

@PublishedApi
internal fun invokeAutoMatchedMethod(
    owner: Class<*>,
    receiver: Any?,
    methodName: String,
    args: Array<out Any?>,
): Any? {
    val method: Method = if (args.any { it == null }) {
        findMethodBestMatch(owner, methodName, *args)
    } else {
        val types = inferArgTypes(args)
        owner.methodOrNull(methodName, argTypes(*types))
            ?: findMethodBestMatch(owner, methodName, *types)
    }
    return method.invoke(receiver, *args)
}

@PublishedApi
internal fun constructAutoMatchedInstance(owner: Class<*>, args: Array<out Any?>): Any {
    if (args.isEmpty()) {
        return owner.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
    }
    val constructor = if (args.any { it == null }) {
        findConstructorBestMatch(owner, *args)
    } else {
        val types = inferArgTypes(args)
        runCatching { owner.getDeclaredConstructor(*types).also { it.isAccessible = true } }.getOrNull()
            ?: findConstructorBestMatch(owner, *types)
    }
    return constructor.newInstance(*args)
}

/**
 * 浅拷贝字段值：将 [src] 的所有字段值复制到 [dst]。
 *
 * 遍历 src 的 declaredFields（含父类），对每个 field 执行 get(src) → set(dst, value)。
 * 引用类型字段只拷贝引用（浅拷贝）。
 *
 * ```kotlin
 * val newConfig = configClass.newInstance()
 * fieldCpy(src = oldConfig, dst = newConfig)
 * ```
 *
 * @param src       源对象
 * @param dst       目标对象（必须与 src 是同一类型或其子类）
 * @param findSuper 是否拷贝父类字段，默认 true
 */
fun fieldCpy(src: Any, dst: Any, findSuper: Boolean = true) {
    var clz: Class<*>? = src.javaClass
    while (clz != null && clz != Any::class.java) {
        for (field in clz.declaredFields) {
            field.isAccessible = true
            try {
                field.set(dst, field.get(src))
            } catch (_: Throwable) {
                // skip fields that cannot be set (e.g. final static)
            }
        }
        if (!findSuper) break
        clz = clz.superclass
    }
}

@file:JvmName("ArgsHelper")

package io.github.lingqiqi5211.ezhooktool.core

/**
 * 方法参数值包装。
 *
 * 解决 Kotlin `vararg Any?` 在多参数重载时的歧义。
 *
 * ```kotlin
 * instance.callMethod("doTask", args("hello", 42))
 * ```
 *
 * **Java 调用方注意**：[Args] 是 Kotlin `@JvmInline value class`，在 Java 字节码层会被擦除为 `Object[]`。
 * 也就是说 `Args.of(...)` 实际返回 `Object[]`，**无法直接传给签名为 `Args` 的 Kotlin 函数**。
 * Java 请改用 [io.github.lingqiqi5211.ezhooktool.core.java.Methods.callMethod] 等专为 Java 设计的入口。
 *
 * @property args 实际要传给目标方法或构造器的参数数组
 */
@JvmInline
value class Args(val args: Array<out Any?>) {
    companion object {
        /**
         * Kotlin 用：`Args.of("hello", 42)`。
         *
         * 仅供 Kotlin 调用方使用；Java 端因为 inline class erasure 拿到的是 `Object[]`，请用 Java facade。
         *
         * @param args 实际要传给目标方法或构造器的参数数组
         */
        @JvmStatic
        fun of(vararg args: Any?): Args = Args(args)
    }
}

/**
 * 方法参数类型包装。
 *
 * ```kotlin
 * instance.callMethod("doTask", args("hello", 42), argTypes(String::class.java, Int::class.java))
 * ```
 *
 * **Java 调用方注意**：[ArgTypes] 同 [Args]，会被擦除为 `Class[]`，无法直接传给签名为 `ArgTypes` 的 Kotlin 函数。
 *
 * @property types 参数类型数组
 */
@JvmInline
value class ArgTypes(val types: Array<out Class<*>>) {
    companion object {
        /**
         * Kotlin 用：`ArgTypes.of(String::class.java, Int::class.javaPrimitiveType!!)`。
         *
         * @param types 参数类型数组
         */
        @JvmStatic
        fun of(vararg types: Class<*>): ArgTypes = ArgTypes(types)
    }
}

/**
 * 创建 [Args]: `args("hello", 42)`
 *
 * @param args 实际要传给目标方法或构造器的参数数组
 */
fun args(vararg args: Any?): Args = Args(args)

/**
 * 创建 [ArgTypes]: `argTypes(String::class.java, Int::class.java)`
 *
 * @param types 参数类型数组
 */
fun argTypes(vararg types: Class<*>): ArgTypes = ArgTypes(types)

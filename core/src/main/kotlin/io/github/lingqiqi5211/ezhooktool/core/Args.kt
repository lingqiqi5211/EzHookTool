@file:JvmName("ArgsHelper")

package io.github.lingqiqi5211.ezhooktool.core

/**
 * 方法参数值包装。
 *
 * 解决 Kotlin `vararg Any?` 在多参数重载时的歧义。
 *
 * ```kotlin
 * instance.invokeMethod("doTask", args("hello", 42))
 * ```
 *
 * @property args 实际要传给目标方法或构造器的参数数组
 */
@JvmInline
value class Args(val args: Array<out Any?>) {
    companion object {
        /**
         * Java 用: `Args.of("hello", 42)`
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
 * instance.invokeMethod("doTask", args("hello", 42), argTypes(String::class.java, Int::class.java))
 * ```
 *
 * @property types 参数类型数组
 */
@JvmInline
value class ArgTypes(val types: Array<out Class<*>>) {
    companion object {
        /**
         * Java 用: `ArgTypes.of(String.class, int.class)`
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

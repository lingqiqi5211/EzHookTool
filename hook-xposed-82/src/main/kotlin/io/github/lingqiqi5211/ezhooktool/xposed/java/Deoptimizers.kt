package io.github.lingqiqi5211.ezhooktool.xposed.java

import io.github.lingqiqi5211.ezhooktool.xposed.internal.DeoptimizeBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 供 Java 调用的去优化入口。
 */
object Deoptimizers {
    /**
     * 对指定方法做去优化。
     *
     * @param method 目标方法
     */
    @JvmStatic
    fun deoptimize(method: Method): Boolean = DeoptimizeBridge.deoptimize(method)

    /**
     * 对指定构造器做去优化。
     *
     * @param constructor 目标构造器
     */
    @JvmStatic
    fun deoptimize(constructor: Constructor<*>): Boolean = DeoptimizeBridge.deoptimize(constructor)

    /**
     * 按方法名批量对类中的方法做去优化。
     *
     * @param clazz 目标类
     * @param names 方法名列表
     */
    @JvmStatic
    fun deoptimizeMethods(clazz: Class<*>, vararg names: String) = DeoptimizeBridge.deoptimizeMethods(clazz, *names)
}

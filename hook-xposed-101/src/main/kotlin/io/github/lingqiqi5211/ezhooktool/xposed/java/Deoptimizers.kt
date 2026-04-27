package io.github.lingqiqi5211.ezhooktool.xposed.java

import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
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
    fun deoptimize(method: Method): Boolean = EzXposed.deoptimize(method)

    /**
     * 对指定构造器做去优化。
     *
     * @param constructor 目标构造器
     */
    @JvmStatic
    fun deoptimize(constructor: Constructor<*>): Boolean = EzXposed.deoptimize(constructor)
}

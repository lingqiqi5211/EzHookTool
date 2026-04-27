package io.github.lingqiqi5211.ezhooktool.core.java

import io.github.lingqiqi5211.ezhooktool.core.EzReflect

/**
 * 供 Java 调用的类查找入口。
 *
 * Kotlin 代码通常直接使用 `findClass`、`findClassOrNull` 或字符串扩展。
 */
object Classes {
    /**
     * 按完整类名查找类，找不到时抛出异常。
     *
     * @param name 完整类名，例如 `com.example.Target`
     * @param classLoader 用于加载目标类的 `ClassLoader`
     */
    @JvmStatic
    @JvmOverloads
    fun findClass(name: String, classLoader: ClassLoader = EzReflect.classLoader): Class<*> =
        io.github.lingqiqi5211.ezhooktool.core.findClass(name, classLoader)

    /**
     * 按完整类名查找类，找不到时返回 `null`。
     *
     * @param name 完整类名，例如 `com.example.Target`
     * @param classLoader 用于加载目标类的 `ClassLoader`
     */
    @JvmStatic
    @JvmOverloads
    fun findClassOrNull(name: String, classLoader: ClassLoader = EzReflect.classLoader): Class<*>? =
        io.github.lingqiqi5211.ezhooktool.core.findClassOrNull(name, classLoader)

    /**
     * 按顺序尝试多个类名，返回第一个能加载的类。
     *
     * @param names 候选类名，越靠前优先级越高
     */
    @JvmStatic
    fun findFirstClass(vararg names: String): Class<*> =
        io.github.lingqiqi5211.ezhooktool.core.findFirstClass(*names)

    /**
     * 使用指定 `ClassLoader` 按顺序尝试多个类名，返回第一个能加载的类。
     *
     * @param classLoader 用于加载候选类的 `ClassLoader`
     * @param names 候选类名，越靠前优先级越高
     */
    @JvmStatic
    fun findFirstClass(classLoader: ClassLoader, vararg names: String): Class<*> =
        io.github.lingqiqi5211.ezhooktool.core.findFirstClass(*names, classLoader = classLoader)

    /**
     * 按顺序尝试多个类名，全部找不到时返回 `null`。
     *
     * @param names 候选类名，越靠前优先级越高
     */
    @JvmStatic
    fun findFirstClassOrNull(vararg names: String): Class<*>? =
        io.github.lingqiqi5211.ezhooktool.core.findFirstClassOrNull(*names)

    /**
     * 使用指定 `ClassLoader` 按顺序尝试多个类名，全部找不到时返回 `null`。
     *
     * @param classLoader 用于加载候选类的 `ClassLoader`
     * @param names 候选类名，越靠前优先级越高
     */
    @JvmStatic
    fun findFirstClassOrNull(classLoader: ClassLoader, vararg names: String): Class<*>? =
        io.github.lingqiqi5211.ezhooktool.core.findFirstClassOrNull(*names, classLoader = classLoader)
}

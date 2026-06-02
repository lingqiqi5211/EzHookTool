package io.github.lingqiqi5211.ezhooktool.core

/**
 * 类名枚举策略。
 *
 * `core` 不直接扫描 Android Dex。需要条件查找类时，应替换为能从目标 [ClassLoader] 枚举类名的实现。
 */
interface ClassResolver {
    /** 返回当前 [classLoader] 下可参与条件查找的类名。 */
    fun classNamesOf(classLoader: ClassLoader): Sequence<String>
}

internal object DefaultClassResolver : ClassResolver {
    override fun classNamesOf(classLoader: ClassLoader): Sequence<String> =
        emptySequence()
}

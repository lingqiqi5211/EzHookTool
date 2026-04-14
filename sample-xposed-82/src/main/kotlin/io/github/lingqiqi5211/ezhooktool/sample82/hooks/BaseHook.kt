package io.github.lingqiqi5211.ezhooktool.sample82.hooks

abstract class BaseHook {
    abstract fun init()

    abstract val name: String

    var isInit: Boolean = false
}

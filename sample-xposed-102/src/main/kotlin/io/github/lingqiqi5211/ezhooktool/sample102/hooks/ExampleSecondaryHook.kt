package io.github.lingqiqi5211.ezhooktool.sample102.hooks

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

/** 第二个作用域独立安装的示例 hook。 */
object ExampleSecondaryHook : BaseHook() {
    override val name: String = "ExampleSecondaryHook"

    override fun init() {
        "com.example.secondary.Feature".findMethod {
            name("isEnabled")
        }.createBeforeHook { param ->
            param.result = true
        }
    }
}

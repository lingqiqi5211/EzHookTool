package io.github.lingqiqi5211.ezhooktool.sample82.hooks

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook

object ExampleVipHook : BaseHook() {
    override val name: String = "ExampleVipHook"

    override fun init() {
        "com.example.target.License".findMethod {
            name("isVip")
        }.createReplaceHook { true }
    }
}

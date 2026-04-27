package io.github.lingqiqi5211.ezhooktool.sample82.hooks

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

object ExampleReporterHook : BaseHook() {
    override val name: String = "ExampleReporterHook"

    private var installed = false

    override fun init() {
        val outer = "com.example.target.UserManager".findMethod { name("login") }
        val inner = "com.example.target.Reporter".findMethod { name("trackLogin") }
        outer.createBeforeHook {
            if (!installed) {
                installed = true
                inner.createBeforeHook { innerParam ->
                    innerParam.result = null
                }
            }
        }
    }
}

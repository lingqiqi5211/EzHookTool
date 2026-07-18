package io.github.lingqiqi5211.ezhooktool.sample82.hooks

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

object ExampleReporterHook : BaseHook() {
    override val name: String = "ExampleReporterHook"

    private var loginObserved = false

    override fun init() {
        val outer = "com.example.target.UserManager".findMethod { name("login") }
        val inner = "com.example.target.Reporter".findMethod { name("trackLogin") }
        outer.createBeforeHook {
            loginObserved = true
        }
        inner.createBeforeHook { innerParam ->
            if (loginObserved) {
                innerParam.result = null
            }
        }
    }
}

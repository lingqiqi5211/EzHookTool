package io.github.lingqiqi5211.ezhooktool.sample102.hooks

import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

object ExampleReporterHook : BaseHook() {
    override val name: String = "ExampleReporterHook"

    private var loginObserved = false

    override fun init() {
        val outer = "com.example.target.UserManager".findMethod { name("login") }
        val inner = "com.example.target.Reporter".findMethod { name("trackLogin") }
        // 两个 hook 都在 target-ready 同步阶段安装，避免把“首次调用时再挂 inner hook”
        // 这种延迟注册混进一次无法原子收尾的热重载。
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

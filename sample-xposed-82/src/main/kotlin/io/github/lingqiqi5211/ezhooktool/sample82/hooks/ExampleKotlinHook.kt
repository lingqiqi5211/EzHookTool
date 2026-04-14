package io.github.lingqiqi5211.ezhooktool.sample82.hooks

import android.app.Application
import android.util.Log
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed82.helper.createHook

object ExampleKotlinHook : BaseHook() {
    override val name: String = "ExampleKotlinHook"

    override fun init() {
        Application::class.java.findMethod(findSuper = false) {
            name == "onCreate" && parameterCount == 0
        }.createHook {
            before {
                Log.i(name, "Hello, Kotlin before hook!")
            }

            after {
                Log.i(name, "Hello, Kotlin after hook!")
            }
        }
    }
}

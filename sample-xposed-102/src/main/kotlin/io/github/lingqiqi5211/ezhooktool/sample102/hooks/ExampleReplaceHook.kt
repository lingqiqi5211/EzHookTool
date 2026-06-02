package io.github.lingqiqi5211.ezhooktool.sample102.hooks

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.id
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.replaceWith

/**
 * 演示 hook id 与运行时替换。
 *
 * 第一次注册时为 hook 设置了 id，便于在 onHotReloaded 中重新挂钩或运行时切换实现。
 */
object ExampleReplaceHook : BaseHook() {
    override val name: String = "ExampleReplaceHook"

    /** 暴露给 MainHook，用于热重载场景下的句柄迁移。 */
    var handle: XposedInterface.HookHandle? = null
        private set

    override fun init() {
        handle = "com.example.target.RemoteConfig".findMethod {
            name("getBoolean")
            params(String::class.java)
        }.createHook {
            id(HookId)
            before { param ->
                Log.i(name, "before, key=${param.argAs<String>(0)}, id=${handle?.id}")
            }
            after { param ->
                if (param.argAs<String>(0) == "premium_unlocked") {
                    param.result = true
                }
            }
        }
    }

    /** 运行时切换实现：替换为「无论参数，永远返回 true」的 replace 行为。 */
    fun switchToAlwaysTrue() {
        val current = handle ?: return
        handle = current.replaceWith { true }
    }

    const val HookId = "remote-config-getBoolean"
}

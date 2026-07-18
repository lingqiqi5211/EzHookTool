package io.github.lingqiqi5211.ezhooktool.sample102.hooks

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.id
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.replaceWith

/**
 * 演示稳定 reload key 与运行时替换。
 *
 * 同一 key 会在热重载时保持可替换；句柄也可用于运行时切换实现。
 */
object ExampleReplaceHook : BaseHook() {
    override val name: String = "ExampleReplaceHook"

    /** 用于运行时切换实现的当前句柄。 */
    var handle: XposedInterface.HookHandle? = null
        private set

    override fun init() {
        handle = "com.example.target.RemoteConfig".findMethod {
            name("getBoolean")
            params(String::class.java)
        }.createHook {
            reloadKey(HookId)
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
        val current = requireNotNull(handle) { "ExampleReplaceHook.handle is not initialized. Call init() first." }
        handle = current.replaceWith { true }
    }

    const val HookId = "remote-config-getBoolean"
}

@file:JvmName("HookHandles")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.common.InterceptChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.common.ReplaceChainStage

/**
 * 当前 hook 的 id。
 *
 * 等价于 [XposedInterface.HookHandle.getId]，未通过 [HookFactory.id] 设置过时为 `null`。
 */
val XposedInterface.HookHandle.id: String?
    get() = getId()

/**
 * 用一个 replace 风格的 lambda 原子替换当前 hook，返回新 handle。
 *
 * 等价于「丢弃旧 hook 的全部 before/after/intercept 行为，新行为只生成返回值」。
 *
 * 上游约束：替换会保留原 hook 的 executable、priority、exceptionMode 和 id；
 * 替换成功后当前 handle 即失效，再调用其它方法会抛出 [IllegalStateException]。
 * 若需要直接传 [XposedInterface.Hooker]，调用 [XposedInterface.HookHandle.replaceHook] 即可。
 *
 * @param callback 生成替代返回值的回调，会在 [io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.safeMode]
 *   打开时享受同样的保护
 */
@JvmSynthetic
fun XposedInterface.HookHandle.replaceWith(
    callback: (HookParam) -> Any?,
): XposedInterface.HookHandle {
    val hooker = buildHooker(executable, listOf(ReplaceChainStage(callback)))
    return replaceHook(hooker)
}

/**
 * 用一个 intercept 风格的 lambda 原子替换当前 hook，返回新 handle。
 *
 * 适合需要直接操作 [XposedInterface.Chain] 的场景。语义与 [HookFactory.intercept] 完全一致。
 * 行为约束与 [replaceWith] 一致：保留 executable、priority、exceptionMode、id；旧 handle 失效。
 *
 * @param callback 接收 [XposedInterface.Chain] 的 around 回调
 */
@JvmSynthetic
fun XposedInterface.HookHandle.replaceIntercept(
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle {
    val hooker = buildHooker(executable, listOf(InterceptChainStage(callback)))
    return replaceHook(hooker)
}

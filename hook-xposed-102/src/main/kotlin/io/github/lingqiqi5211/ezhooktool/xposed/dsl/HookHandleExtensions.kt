@file:JvmName("HookHandles")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.common.InterceptChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.common.ReplaceChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.RequiresXposedApi
import io.github.lingqiqi5211.ezhooktool.xposed.XposedFeature
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat

/**
 * 当前 hook 的底层 hook ID。
 *
 * 等价于 [XposedInterface.HookHandle.getId]。默认自动模式会返回聚合批次的逻辑 handle，其物理 hook ID
 * 属于工具内部，因此该值为 `null`；显式设置 [HookFactory.id] / `reloadKey(...)` 时返回对应底层 ID。
 *
 * hook ID 是 libxposed API 102 能力；framework 只实现 API 101 时恒为 `null`，与「这个 hook 没有 ID」
 * 的含义一致。
 *
 * 注意 Kotlin 的解析规则：`handle.id` 会优先命中 `getId()` 的 Java 合成属性而不是本扩展，因此在可能
 * 运行于 101 framework 的代码里请显式调用 `HookHandles.getId(handle)`（Java）或先判断
 * [io.github.lingqiqi5211.ezhooktool.xposed.XposedFeature.HOOK_ID] 的 `isSupported`。
 */
val XposedInterface.HookHandle.id: String?
    get() = XposedApiCompat.hookId(this)

/**
 * 用一个 replace 风格的 lambda 原子替换当前 hook，返回新 handle。
 *
 * 等价于「丢弃旧 hook 的全部 before/after/intercept 行为，新行为只生成返回值」。
 *
 * 上游约束：替换会保留原 hook 的 executable、priority、exceptionMode 和 hook ID；
 * 替换成功后当前 handle 即失效，再调用其它方法会抛出 [IllegalStateException]。
 * 若需要直接传 [XposedInterface.Hooker]，调用 [XposedInterface.HookHandle.replaceHook] 即可。
 *
 * @param callback 生成替代返回值的回调，会在 [io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.safeMode]
 *   打开时享受同样的保护
 */
@JvmSynthetic
@RequiresXposedApi(102)
fun XposedInterface.HookHandle.replaceWith(
    callback: (HookParam) -> Any?,
): XposedInterface.HookHandle {
    XposedApiCompat.requireFeature(XposedFeature.REPLACE_HOOK, "HookHandle.replaceWith")
    val hooker = buildHooker(executable, listOf(ReplaceChainStage(callback)))
    return XposedApiCompat.Api102.replaceHook(this, hooker)
}

/**
 * 用一个 intercept 风格的 lambda 原子替换当前 hook，返回新 handle。
 *
 * 适合需要直接操作 [XposedInterface.Chain] 的场景。语义与 [HookFactory.intercept] 完全一致。
 * 行为约束与 [replaceWith] 一致：保留 executable、priority、exceptionMode、hook ID；旧 handle 失效。
 *
 * @param callback 接收 [XposedInterface.Chain] 的 around 回调
 */
@JvmSynthetic
@RequiresXposedApi(102)
fun XposedInterface.HookHandle.replaceIntercept(
    callback: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle {
    XposedApiCompat.requireFeature(XposedFeature.REPLACE_HOOK, "HookHandle.replaceIntercept")
    val hooker = buildHooker(executable, listOf(InterceptChainStage(callback)))
    return XposedApiCompat.Api102.replaceHook(this, hooker)
}

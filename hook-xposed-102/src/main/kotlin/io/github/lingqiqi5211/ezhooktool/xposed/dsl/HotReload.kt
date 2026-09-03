@file:JvmName("HotReload")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.RequiresXposedApi
import io.github.lingqiqi5211.ezhooktool.xposed.XposedFeature
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat

/**
 * 把旧 hook handle 按底层 hook ID 分桶。
 *
 * 没有 hook ID 的 handle 会落在 `null` key 桶里，调用方自行决定丢弃还是替换。
 * 典型用法：在新 code 的 `onHotReloaded` 里据此挑出感兴趣的旧 handle，并用
 * [XposedInterface.HookHandle.replaceHook]、[replaceWith] 或 [replaceIntercept] 替换。
 */
fun List<XposedInterface.HookHandle>.groupById(): Map<String?, List<XposedInterface.HookHandle>> =
    groupBy(XposedApiCompat::hookId)

/**
 * 用同一个 [XposedInterface.Hooker] 逐个原子替换旧 handle，返回新 handle 列表。
 *
 * 失败语义同 [XposedInterface.HookHandle.replaceHook]：抛出异常时已替换成功的部分保留，
 * 未替换的不会回滚。返回列表的顺序与原列表一致；如果中途抛异常，返回前不包含未处理项。
 */
@RequiresXposedApi(102)
fun List<XposedInterface.HookHandle>.replaceAll(
    hooker: XposedInterface.Hooker,
): List<XposedInterface.HookHandle> {
    XposedApiCompat.requireFeature(XposedFeature.REPLACE_HOOK, "List<HookHandle>.replaceAll")
    return map { XposedApiCompat.Api102.replaceHook(it, hooker) }
}

/**
 * 尝试 unhook 全部 handle；单项失败不会阻止后续清理，结束后统一抛出并附带全部失败原因。
 */
fun List<XposedInterface.HookHandle>.unhookAll() {
    val failures = mutableListOf<Throwable>()
    for (handle in this) {
        try {
            handle.unhook()
        } catch (t: Throwable) {
            failures += t
        }
    }
    if (failures.isNotEmpty()) {
        throw IllegalStateException(
            "Failed to unhook ${failures.size} hook handle(s).",
            failures.first(),
        ).also { error -> failures.drop(1).forEach(error::addSuppressed) }
    }
}

@file:JvmName("HotReload")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface

/**
 * 把旧 hook handle 按 id 分桶。
 *
 * 没有 id 的 handle 会落在 `null` key 桶里，调用方自行决定丢弃还是替换。
 * 典型用法：在新 code 的 `onHotReloaded` 里据此挑出感兴趣的旧 handle，并用
 * [XposedInterface.HookHandle.replaceHook]、[replaceWith] 或 [replaceIntercept] 替换。
 */
fun List<XposedInterface.HookHandle>.groupById(): Map<String?, List<XposedInterface.HookHandle>> =
    groupBy { it.id }

/**
 * 用同一个 [XposedInterface.Hooker] 原子替换全部旧 handle，返回新 handle 列表。
 *
 * 失败语义同 [XposedInterface.HookHandle.replaceHook]：抛出异常时已替换成功的部分保留，
 * 未替换的不会回滚。返回列表的顺序与原列表一致；如果中途抛异常，返回前不包含未处理项。
 */
fun List<XposedInterface.HookHandle>.replaceAll(
    hooker: XposedInterface.Hooker,
): List<XposedInterface.HookHandle> = map { it.replaceHook(hooker) }

/** 全部 unhook。等价于 `HotReloadedParam` 的默认实现，但暴露成可显式调用。 */
fun List<XposedInterface.HookHandle>.unhookAll() {
    forEach { it.unhook() }
}

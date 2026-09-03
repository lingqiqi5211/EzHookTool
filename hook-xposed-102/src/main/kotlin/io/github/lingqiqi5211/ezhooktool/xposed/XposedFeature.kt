package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat

/**
 * libxposed 的可选特性。本模块按 API 102 编译，运行基线是 API 101；102 及以上的能力全部走这里协商。
 *
 * 版本在 [EzXposed.initOnModuleLoaded] 时解析一次，之后 [isSupported] 只是一次位测试。
 * 库内对 102 符号的调用全部收在 [XposedApiCompat.Api102]。
 */
enum class XposedFeature(
    /** 该特性要求的最低 libxposed API 版本。 */
    val minApiVersion: Int,
) {
    /** hook ID：`HookBuilder.setId` 与 `HookHandle.getId`，热重载跨代识别 hook 的基础。 */
    HOOK_ID(102),

    /** 原子替换已安装的 hook：`HookHandle.replaceHook`。 */
    REPLACE_HOOK(102),

    /** 热重载：`onHotReloading` / `onHotReloaded` 回调及其参数类型，依赖 [HOOK_ID]。 */
    HOT_RELOAD(102),

    /** 停止向当前 entry 分发后续生命周期回调：`XposedInterfaceWrapper.detach`。 */
    DETACH_ENTRY(102),
    ;

    /** 在支持掩码里的位。 */
    internal val bit: Int get() = 1 shl ordinal

    /** 当前 framework 是否提供该特性。 */
    val isSupported: Boolean
        get() = XposedApiCompat.isSupported(this)
}

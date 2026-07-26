package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat

/**
 * libxposed 的可选特性。
 *
 * 每个特性对应一组高版本 API 才提供的能力。[isSupported] 按当前 framework 实际提供的类型和方法判定，
 * 而不是只看版本号，因此可以直接用来决定要不要走某条代码路径：
 *
 * ```kotlin
 * if (XposedFeature.HOT_RELOAD.isSupported) {
 *     // 走热重载流程
 * }
 * ```
 *
 * 需要这些特性的公开 API 都标注了 [RequiresXposedApi]。
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

    /** 当前 framework 是否提供该特性。 */
    val isSupported: Boolean
        get() = XposedApiCompat.isSupported(this)
}

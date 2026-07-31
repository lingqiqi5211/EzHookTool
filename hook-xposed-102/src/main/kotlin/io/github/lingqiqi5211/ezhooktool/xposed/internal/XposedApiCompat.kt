package io.github.lingqiqi5211.ezhooktool.xposed.internal

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.XposedFeature

/**
 * [XposedFeature] 的运行时版本协商与统一前置检查。
 *
 * 本模块按 API 102 编译，但高版本能力只能在当前 framework 报告的 API 版本达到
 * [XposedFeature.minApiVersion] 后调用。版本由 libxposed 规定的 [XposedInterface.getApiVersion]
 * 提供，不反射 Xposed API；framework 启用 `PROP_RT_API_PROTECTION` 时，反射会被禁止，但静态 API 调用
 * 与版本协商仍然可用。
 *
 * 未初始化时版本为 0，且不会缓存该结果；[EzXposed.initOnModuleLoaded] 保存 framework 实例后会立即得到
 * 当前 generation 的真实版本。
 */
internal object XposedApiCompat {
    /** 当前 framework 是否提供 [feature]。 */
    fun isSupported(feature: XposedFeature): Boolean =
        apiVersion(EzXposed.baseOrNull) >= feature.minApiVersion

    /**
     * 判断 `ModuleLoadedParam` 是否其实是热重载的 `HotReloadedParam`。
     *
     * 外层先用 API 101 已提供的版本接口短路；102 才存在的类型判断隔离在 [Api102]，
     * API 101 framework 不会加载该路径。
     */
    fun isHotReloadedParam(
        base: XposedInterface,
        param: XposedModuleInterface.ModuleLoadedParam,
    ): Boolean =
        apiVersion(base) >= XposedInterface.API_102 && Api102.isHotReloadedParam(param)

    /**
     * 安全读取 hook ID；[XposedFeature.HOOK_ID] 不可用时返回 `null`。
     *
     * 库内部读取 hook ID 一律走这里。Kotlin 里 `handle.id` 会优先解析成 Java 合成属性（即直接调用
     * `getId()`），而不是本库带兜底的扩展属性，写成 `handle.id` 会在 101 framework 上抛
     * `NoSuchMethodError`。
     */
    fun hookId(handle: XposedInterface.HookHandle): String? =
        if (isSupported(XposedFeature.HOOK_ID)) handle.getId() else null

    /**
     * 标注了 [io.github.lingqiqi5211.ezhooktool.xposed.RequiresXposedApi] 的入口统一在这里做前置检查。
     *
     * @param api 报错信息里显示的 API 名称
     */
    fun requireFeature(feature: XposedFeature, api: String) {
        check(isSupported(feature)) {
            val current = EzXposed.frameworkApiVersion.takeIf { it > 0 }?.toString() ?: "unknown"
            "$api requires libxposed API ${feature.minApiVersion} (${feature.name}); " +
                "the current framework reports API $current."
        }
    }

    /** framework 侧 libxposed API 版本；[base] 为 `null` 或读取失败时返回 0。 */
    fun apiVersion(base: XposedInterface?): Int =
        base?.let { runCatching { it.apiVersion }.getOrDefault(0) } ?: 0

    /**
     * 隔离 102 才存在的类型引用。API 101 路径先在外层按版本返回，不会加载这个类。
     */
    private object Api102 {
        fun isHotReloadedParam(param: XposedModuleInterface.ModuleLoadedParam): Boolean =
            param is XposedModuleInterface.HotReloadedParam
    }
}

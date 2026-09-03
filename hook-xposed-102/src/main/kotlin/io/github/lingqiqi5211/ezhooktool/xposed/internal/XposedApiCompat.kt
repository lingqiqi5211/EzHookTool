package io.github.lingqiqi5211.ezhooktool.xposed.internal

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterfaceWrapper
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.xposed.XposedFeature

/**
 * [XposedFeature] 的运行时协商，以及库内部触碰 102 API 的唯一出口。
 *
 * [resolve] 在 `initOnModuleLoaded` 里把 framework 版本换算成 [supported] 掩码，[isSupported] 只做一次 AND。
 * 未 [resolve] 时掩码为 0，按 101 处理。ART 对方法体里解析不到的符号一执行就抛，所以 102 调用全收进
 * [Api102]；调用前必须已经过特性判断，网关内不重复检查。
 */
internal object XposedApiCompat {

    @Volatile
    private var supported: Int = 0

    @Volatile
    private var resolvedApiVersion: Int = 0

    /** 按 [base] 报告的版本重算支持掩码；读取失败按 0 处理。 */
    fun resolve(base: XposedInterface) {
        val version = runCatching { base.apiVersion }.getOrDefault(0)
        resolvedApiVersion = version
        supported = XposedFeature.entries.fold(0) { mask, feature ->
            if (version >= feature.minApiVersion) mask or feature.bit else mask
        }
    }

    /** 当前 framework 是否提供 [feature]。一次 volatile 读加一次 AND。 */
    fun isSupported(feature: XposedFeature): Boolean = supported and feature.bit != 0

    /** framework 侧 libxposed API 版本；未 [resolve] 时为 0。 */
    val apiVersion: Int get() = resolvedApiVersion

    /** `ModuleLoadedParam` 是否其实是热重载的 `HotReloadedParam`；掩码短路后才进 [Api102]。 */
    fun isHotReloadedParam(param: XposedModuleInterface.ModuleLoadedParam): Boolean =
        isSupported(XposedFeature.HOT_RELOAD) && Api102.isHotReloadedParam(param)

    /**
     * 安全读取 hook ID；[XposedFeature.HOOK_ID] 不可用时返回 `null`。库内一律走这里，不要写 `handle.id`，
     * 它会解析成直接调用 `getId()`，101 上抛 `NoSuchMethodError`。
     */
    fun hookId(handle: XposedInterface.HookHandle): String? =
        if (isSupported(XposedFeature.HOOK_ID)) Api102.hookId(handle) else null

    /**
     * 标注了 [io.github.lingqiqi5211.ezhooktool.xposed.RequiresXposedApi] 的入口统一在这里做前置检查。
     *
     * @param api 报错信息里显示的 API 名称
     */
    fun requireFeature(feature: XposedFeature, api: String) {
        check(isSupported(feature)) {
            val current = resolvedApiVersion.takeIf { it > 0 }?.toString() ?: "unknown"
            "$api requires libxposed API ${feature.minApiVersion} (${feature.name}); " +
                "the current framework reports API $current."
        }
    }

    /** 库内部触碰 102 符号的唯一出口，每个方法都是薄转发；别处出现这些符号会被 checkApi102Gateway 挡下。 */
    object Api102 {
        fun isHotReloadedParam(param: XposedModuleInterface.ModuleLoadedParam): Boolean =
            param is XposedModuleInterface.HotReloadedParam

        fun hookId(handle: XposedInterface.HookHandle): String? = handle.getId()

        fun setId(builder: XposedInterface.HookBuilder, id: String): XposedInterface.HookBuilder =
            builder.setId(id)

        fun replaceHook(
            handle: XposedInterface.HookHandle,
            hooker: XposedInterface.Hooker,
        ): XposedInterface.HookHandle = handle.replaceHook(hooker)

        fun detach(entry: XposedInterfaceWrapper) = entry.detach()

        fun setSavedInstanceState(param: XposedModuleInterface.HotReloadingParam, state: Array<Any?>) =
            param.setSavedInstanceState(state)

        // libxposed 声明的返回类型就是 Object，TargetSnapshot 自己做形状校验，这里不收窄。
        fun savedInstanceState(param: XposedModuleInterface.HotReloadedParam): Any? =
            param.savedInstanceState

        fun oldHookHandles(param: XposedModuleInterface.HotReloadedParam): List<XposedInterface.HookHandle> =
            param.oldHookHandles
    }
}

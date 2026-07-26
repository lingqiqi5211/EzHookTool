package io.github.lingqiqi5211.ezhooktool.xposed.internal

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterfaceWrapper
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.XposedFeature

/**
 * [XposedFeature] 的运行时探测与统一前置检查。
 *
 * 本模块按 API 102 编译，但 102 相对 101 只新增了几处能力：`HookBuilder.setId`、`HookHandle.getId` /
 * `replaceHook`、`XposedInterfaceWrapper.detach`，以及 `onHotReloading` / `onHotReloaded` 那套热重载回调
 * 与参数类型。这些类型和方法在 101 framework 上不存在，所有会走到它们的代码路径都必须先经过这里的探测，
 * 绝不能出现在无条件执行的路径上。
 *
 * 探测一律走反射：反射失败只会拿到 `false`，而直接引用缺失的类型 / 方法会抛 `NoClassDefFoundError`
 * 或 `NoSuchMethodError`。结果按特性缓存，热路径上只是一次 map 读取。
 */
internal object XposedApiCompat {
    private val probeResults = HashMap<XposedFeature, Boolean>(XposedFeature.entries.size)

    /** 当前 framework 是否提供 [feature]。 */
    fun isSupported(feature: XposedFeature): Boolean = synchronized(probeResults) {
        probeResults.getOrPut(feature) { probe(feature) }
    }

    /**
     * 判断 `ModuleLoadedParam` 是否其实是热重载的 `HotReloadedParam`。
     *
     * 用反射而不是 `is` / `as?`：这个判断位于 [EzXposed.initOnModuleLoaded] 这类必经路径上，
     * 直接引用 102 才有的类型会让 101 framework 上的初始化直接崩掉。
     */
    fun isHotReloadedParam(param: Any?): Boolean =
        param != null && hotReloadedParamClass?.isInstance(param) == true

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

    private fun probe(feature: XposedFeature): Boolean = when (feature) {
        XposedFeature.HOOK_ID ->
            hasMethod(XposedInterface.HookBuilder::class.java, "setId", String::class.java) &&
                hasMethod(XposedInterface.HookHandle::class.java, "getId")

        XposedFeature.REPLACE_HOOK ->
            hasMethod(
                XposedInterface.HookHandle::class.java,
                "replaceHook",
                XposedInterface.Hooker::class.java,
            )

        // 直接复用 probe 而不是 isSupported：避免在缓存写入过程中重入同一张表。
        XposedFeature.HOT_RELOAD ->
            probe(XposedFeature.HOOK_ID) && hotReloadedParamClass != null

        XposedFeature.DETACH_ENTRY ->
            hasMethod(XposedInterfaceWrapper::class.java, "detach")
    }

    private val hotReloadedParamClass: Class<*>? by lazy {
        runCatching {
            Class.forName(
                "io.github.libxposed.api.XposedModuleInterface\$HotReloadedParam",
                false,
                XposedInterface::class.java.classLoader,
            )
        }.getOrNull()
    }

    private fun hasMethod(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Boolean =
        runCatching { owner.getMethod(name, *parameterTypes) }.isSuccess
}

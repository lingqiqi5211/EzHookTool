package io.github.lingqiqi5211.ezhooktool.xposed.internal

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterfaceWrapper
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
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
    private const val TAG = "HC-Reload"
    private val probeResults = HashMap<XposedFeature, Boolean>(XposedFeature.entries.size)

    /** 当前 framework 是否提供 [feature]。 */
    fun isSupported(feature: XposedFeature): Boolean = synchronized(probeResults) {
        probeResults.getOrPut(feature) { 
            val result = probe(feature)
            EzReflect.logger.debug(TAG, "Feature ${feature.name} supported: $result")
            result
        }
    }

    /**
     * 判断 `ModuleLoadedParam` 是否其实是热重载的 `HotReloadedParam`。
     */
    fun isHotReloadedParam(param: Any?): Boolean {
        val clazz = hotReloadedParamClass
        val isInstance = param != null && clazz?.isInstance(param) == true
        if (param != null && clazz == null) {
            EzReflect.logger.warn(TAG, "hotReloadedParamClass is null, cannot detect if param is HotReloadedParam")
        }
        return isInstance
    }

    /**
     * 安全读取 hook ID；[XposedFeature.HOOK_ID] 不可用时返回 `null`。
     */
    fun hookId(handle: XposedInterface.HookHandle): String? =
        if (isSupported(XposedFeature.HOOK_ID)) handle.getId() else null

    /**
     * 标注了 [io.github.lingqiqi5211.ezhooktool.xposed.RequiresXposedApi] 的入口统一在这里做前置检查。
     */
    fun requireFeature(feature: XposedFeature, api: String) {
        if (!isSupported(feature)) {
            val current = EzXposed.frameworkApiVersion.takeIf { it > 0 }?.toString() ?: "unknown"
            val msg = "$api requires libxposed API ${feature.minApiVersion} (${feature.name}); the current framework reports API $current."
            EzReflect.logger.error(TAG, msg)
            throw IllegalStateException(msg)
        }
    }

    /** framework 侧 libxposed API 版本；[base] 为 `null` 或读取失败时返回 0。 */
    fun apiVersion(base: XposedInterface?): Int =
        base?.let { runCatching { it.apiVersion }.getOrDefault(0) } ?: 0

    private fun probe(feature: XposedFeature): Boolean = when (feature) {
        XposedFeature.HOOK_ID -> {
            val hasSetId = hasMethod(XposedInterface.HookBuilder::class.java, "setId", String::class.java)
            val hasGetId = hasMethod(XposedInterface.HookHandle::class.java, "getId")
            if (!hasSetId || !hasGetId) {
                EzReflect.logger.warn(TAG, "HOOK_ID check failed: setId=$hasSetId, getId=$hasGetId")
            }
            hasSetId && hasGetId
        }

        XposedFeature.REPLACE_HOOK ->
            hasMethod(
                XposedInterface.HookHandle::class.java,
                "replaceHook",
                XposedInterface.Hooker::class.java,
            )

        XposedFeature.HOT_RELOAD -> {
            val hookIdSupported = probe(XposedFeature.HOOK_ID)
            val paramClassExists = hotReloadedParamClass != null
            if (!hookIdSupported || !paramClassExists) {
                EzReflect.logger.warn(TAG, "HOT_RELOAD check failed: hookIdSupported=$hookIdSupported, hotReloadedParamClassExists=$paramClassExists")
            }
            hookIdSupported && paramClassExists
        }

        XposedFeature.DETACH_ENTRY ->
            hasMethod(XposedInterfaceWrapper::class.java, "detach")
    }

    private val hotReloadedParamClass: Class<*>? by lazy {
        val parentClass = io.github.libxposed.api.XposedModuleInterface::class.java
        
        // 在部分环境下 declaredClasses 可能为空。
        // 我们改用“参数溯源法”：从 XposedModuleInterface 定义的生命周期方法签名中直接提取类型。
        var found = parentClass.methods.find { it.name == "onHotReloaded" }?.parameterTypes?.firstOrNull()
        
        if (found == null) {
            // 尝试从本地加载器补丁
            val className = "io.github.libxposed.api.XposedModuleInterface\$HotReloadedParam"
            val loaders = listOfNotNull(
                EzXposed::class.java.classLoader,
                XposedInterface::class.java.classLoader,
                Thread.currentThread().contextClassLoader
            )
            for (loader in loaders) {
                runCatching {
                    found = Class.forName(className, false, loader)
                }
                if (found != null) break
            }
        }
        
        if (found != null) {
            val name = found!!.name
            EzReflect.logger.debug(TAG, "Successfully identified HotReloadedParam class: $name")
        } else {
            EzReflect.logger.error(TAG, "CRITICAL: Failed to identify HotReloadedParam class via method signature or forName. " +
                    "Methods available: ${parentClass.methods.joinToString { it.name }}")
        }
        found
    }

    private fun hasMethod(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Boolean =
        runCatching { owner.getMethod(name, *parameterTypes) }.onFailure {
            EzReflect.logger.warn(TAG, "Method $name not found in ${owner.name}")
        }.isSuccess
}

package io.github.lingqiqi5211.ezhooktool.xposed.internal

import android.content.res.Resources
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Method

/** `EzResources` 依赖的 framework 相关能力；82 与 102 各有一份同名实现。 */
internal object ResourcesPlatform {
    const val initEntryPoint = "EzXposed.initOnModuleLoaded"

    val modulePathOrNull: String? get() = EzXposed.modulePathOrNull

    val moduleResourcesOrNull: Resources? get() = EzXposed.moduleResOrNull

    fun requireInitialized() {
        checkNotNull(EzXposed.baseOrNull) { "EzResources requires $initEntryPoint to be called first." }
    }

    fun hookBefore(method: Method, key: String, callback: (HookParam) -> Unit) {
        method.createHook {
            reloadKey(key)
            before(callback)
        }
    }
}

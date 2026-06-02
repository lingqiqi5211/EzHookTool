package io.github.lingqiqi5211.ezhooktool.sample102

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.BaseHook
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleCryptoHook
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleReplaceHook
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleReporterHook
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleVipHook
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.unhookAll

private const val TargetApp = "com.example.target"
private const val TAG = "MainHook"

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        Log.i(TAG, "module loaded, hotReloadPermitted=${EzXposed.isHotReloadPermitted}")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TargetApp) return

        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TargetApp) {
            // 这个进程不是目标 app，后续生命周期回调不再需要分发到当前 entry。
            EzXposed.detachCurrentEntry()
            return
        }

        EzXposed.initOnPackageReady(param)
        initHooks(ExampleVipHook, ExampleCryptoHook, ExampleReporterHook, ExampleReplaceHook)
    }

    /**
     * 旧 code 阶段：透传 extras 给新 code。
     *
     * 不要把旧 module classloader 的对象塞进 [HotReloadingParam.setSavedInstanceState]，
     * framework 会拒绝。这里只传 [Bundle] 里的基础类型。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val outState = Bundle().apply {
            putString("triggeredBy", param.extras?.getString("triggeredBy") ?: "auto")
        }
        param.setSavedInstanceState(outState)
        return true
    }

    /**
     * 新 code 阶段：framework 不会自动重放 package 生命周期。这里只演示如何处理旧 handle：
     * 直接全部 unhook，再以新代码重新挂钩。如果想保留某些 hook，可以用 [groupById] +
     * [io.github.lingqiqi5211.ezhooktool.xposed.dsl.replaceAll]。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        EzXposed.initOnHotReloaded(this, param)

        val savedState = param.savedInstanceState as? Bundle
        Log.i(TAG, "hot reloaded, triggeredBy=${savedState?.getString("triggeredBy")}")

        param.oldHookHandles.unhookAll()

        // 注意：framework 不会重放 onPackageReady。如果需要 EzReflect.classLoader 指向
        // 目标进程，调用方需要自己缓存上一代 package classloader 并重新调用 initOnPackageReady。
        // 这里 sample 只演示句柄处理，不重建反射 classLoader。
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (hook in hooks) {
            try {
                if (hook.isInit) continue
                hook.init()
                hook.isInit = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize hook: ${hook.name}", e)
            }
        }
    }
}

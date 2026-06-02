package io.github.lingqiqi5211.ezhooktool.sample102

import android.os.Build
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

private const val TargetApp = "com.example.target"
private const val TAG = "MainHook"

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // 注册「目标进程准备好后跑什么」。初次加载和热重载后 EzXposed 都会自动触发一次。
        EzXposed.onTargetReady {
            initHooks(ExampleVipHook, ExampleCryptoHook, ExampleReporterHook, ExampleReplaceHook)
        }
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
    }

    // 启用热重载只需要下面两个一行模板。EzXposed 会自动透传目标进程 snapshot，
    // 在新 code 里 unhook 上一代 handle、重建 EzReflect.classLoader 并再次触发 onTargetReady。
    override fun onHotReloading(param: HotReloadingParam): Boolean =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) =
        EzXposed.handleHotReloaded(this, param)

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

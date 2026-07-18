package io.github.lingqiqi5211.ezhooktool.sample102

import android.os.Build
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

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
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
            EzXposed.detachCurrentEntry()
            return
        }
        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        EzXposed.handleHotReloadedWithTargetReady(
            this,
            param,
            targetReady = {
                initHooks(ExampleVipHook, ExampleCryptoHook, ExampleReporterHook, ExampleReplaceHook)
            },
        )
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (hook in hooks) {
            if (hook.isInit) continue
            hook.init()
            hook.isInit = true
        }
    }
}

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
import io.github.lingqiqi5211.ezhooktool.xposed.HotReloadSession

private const val TargetApp = "com.example.target"
private const val TAG = "MainHook"

class MainHook : XposedModule() {
    private val hotReload = HotReloadSession()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // 初次加载和热重载后都会触发。session 会要求每个 hook 声明稳定 reloadKey，
        // 让新旧实现由 libxposed 原子替换，而不是先把所有旧 hook 摘掉。
        hotReload.onTargetReady {
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

    // autoHotReload=true 时，Xposed 应用更新模块后也会走这里。
    override fun onHotReloading(param: HotReloadingParam): Boolean =
        hotReload.prepare(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        val result = hotReload.restore(this, param)
        Log.i(TAG, "Hot reload finished: $result")
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (hook in hooks) {
            if (hook.isInit) continue
            hook.init()
            hook.isInit = true
        }
    }
}

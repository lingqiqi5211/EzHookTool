package io.github.lingqiqi5211.ezhooktool.sample82

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lingqiqi5211.ezhooktool.sample82.hooks.BaseHook
import io.github.lingqiqi5211.ezhooktool.sample82.hooks.ExampleJavaHook
import io.github.lingqiqi5211.ezhooktool.sample82.hooks.ExampleKotlinHook
import io.github.lingqiqi5211.ezhooktool.sample82.hooks.ExampleReporterHook
import io.github.lingqiqi5211.ezhooktool.sample82.hooks.ExampleVipHook
import io.github.lingqiqi5211.ezhooktool.xposed82.EzXposed

private const val TargetApp = "com.example.target"

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TargetApp) return

        EzXposed.init(lpparam)
        initHooks(ExampleKotlinHook, ExampleJavaHook.INSTANCE, ExampleVipHook, ExampleReporterHook)
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (hook in hooks) {
            try {
                if (hook.isInit) continue
                hook.init()
                hook.isInit = true
            } catch (e: Exception) {
                Log.e("MainHook", "Failed to initialize hook: ${hook.name}", e)
            }
        }
    }
}

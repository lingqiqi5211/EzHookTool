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
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleResourceHook
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleSecondaryHook
import io.github.lingqiqi5211.ezhooktool.sample102.hooks.ExampleVipHook
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

private const val PrimaryTarget = "com.example.target"
private const val SecondaryTarget = "com.example.secondary"
private val TargetApps = setOf(PrimaryTarget, SecondaryTarget)

class MainHook : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        EzXposed.onTargetReady {
            installHooksForCurrentScope()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage || param.packageName !in TargetApps) return
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName !in TargetApps) return
        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        EzXposed.handleHotReloadedWithTargetReady(
            this,
            param,
            targetReady = {
                installHooksForCurrentScope()
            },
        )
    }

    /**
     * 每个目标进程都会独立执行一次。开关应从持久设置重新读取，不要跨 generation 保存模块对象。
     * 条件安装支持一个开关管理多个 hook，也支持热重载后全部开关关闭。
     */
    private fun installHooksForCurrentScope() {
        val switches = readHookSwitches()
        when (EzXposed.packageName) {
            PrimaryTarget -> initHooks(
                ExampleVipHook.takeIf { switches.vip },
                ExampleCryptoHook.takeIf { switches.crypto },
                ExampleReporterHook.takeIf { switches.loginReporter },
                ExampleReplaceHook.takeIf { switches.remoteConfig },
                ExampleResourceHook.takeIf { switches.resources },
            )
            SecondaryTarget -> initHooks(
                ExampleSecondaryHook.takeIf { switches.secondaryFeature },
            )
        }
    }

    private fun initHooks(vararg hooks: BaseHook?) {
        hooks.filterNotNull().forEach(BaseHook::init)
    }

    /** 示例使用 remote preferences；模块设置页可按相同 key 写入对应开关。 */
    private fun readHookSwitches(): HookSwitches {
        val preferences = EzXposed.base.getRemotePreferences("hook_switches")
        val scope = "${EzXposed.packageName}:${EzXposed.processName}"
        return HookSwitches(
            vip = preferences.getBoolean("$scope:vip", true),
            crypto = preferences.getBoolean("$scope:crypto", true),
            loginReporter = preferences.getBoolean("$scope:login_reporter", true),
            resources = preferences.getBoolean("$scope:resources", false),
            remoteConfig = preferences.getBoolean("$scope:remote_config", true),
            secondaryFeature = preferences.getBoolean("$scope:secondary_feature", true),
        )
    }

    private data class HookSwitches(
        val vip: Boolean,
        val crypto: Boolean,
        val loginReporter: Boolean,
        val remoteConfig: Boolean,
        /** 默认关：注册过资源替换的进程不能热重载，sample 里别默认把这条路堵死。 */
        val resources: Boolean,
        val secondaryFeature: Boolean,
    )
}

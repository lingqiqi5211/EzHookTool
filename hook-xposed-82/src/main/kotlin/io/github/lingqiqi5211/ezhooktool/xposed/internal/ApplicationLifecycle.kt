package io.github.lingqiqi5211.ezhooktool.xposed.internal

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachSubscription
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.lang.reflect.Method

/**
 * 统一管理 `Application.attach(Context)` 的 hook 与回调分发。
 *
 * 第一次有调用方注册回调时安装 hook；之后所有登记的回调按注册顺序在 attach after 阶段执行。
 * 登记与 attach 分发共用 [ApplicationAttachRegistry] 的锁，Context 探针在锁外读取并校验分发序号，
 * 同一个 attach 不会重复补发；回调异常记日志且不阻断其它回调。
 */
internal object ApplicationLifecycle {
    private const val TAG = "EzXposed.ApplicationLifecycle"

    @Volatile
    private var hookInstalled: Boolean = false

    @Volatile
    private var unhook: XC_MethodHook.Unhook? = null

    /** 旧入口：安装失败只记日志，回调长期保留且没有 token。 */
    fun register(callback: ApplicationAttachCallback) {
        installHookIfNeeded()
        ApplicationAttachRegistry.register(callback, persistent = true) { EzXposed.appContextOrNull }
    }

    /** 一次性登记；attach hook 无法安装时抛出 [IllegalStateException]，不留回调。 */
    fun registerOnce(callback: ApplicationAttachCallback): ApplicationAttachSubscription {
        requireAttachHook()
        return ApplicationAttachRegistry.register(callback, persistent = false) { EzXposed.appContextOrNull }
    }

    /** 可取消的长期登记；attach hook 无法安装时抛出 [IllegalStateException]，不留回调。 */
    fun subscribe(callback: ApplicationAttachCallback): ApplicationAttachSubscription {
        requireAttachHook()
        return ApplicationAttachRegistry.register(callback, persistent = true) { EzXposed.appContextOrNull }
    }

    private fun requireAttachHook() {
        if (installHookIfNeeded()) return
        throw IllegalStateException(
            "Cannot observe Application.attach in this process: " +
                "Application.attach(Context) was not found, so no attach callback was registered.",
        )
    }

    /**
     * 安装 attach hook；找不到 `Application.attach(Context)` 时返回 `false` 且不标记已安装，
     * 让新入口明确失败、旧入口只记日志。
     */
    private fun installHookIfNeeded(): Boolean {
        if (hookInstalled) return true
        synchronized(this) {
            if (hookInstalled) return true

            val attachMethod = findAttachMethod() ?: return false

            unhook = installAttachHook(attachMethod)
            hookInstalled = true
            return true
        }
    }

    private fun findAttachMethod(): Method? =
        try {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        } catch (e: NoSuchMethodException) {
            HookDiagnostics.error(TAG, "Application.attach(Context) not found, cannot install hook", e)
            null
        }

    /**
     * hook 安装后不再 unhook——application 生命周期等价进程生命周期。本回调本身按 [EzXposed.safeMode]
     * 兜底，避免污染目标 `Application.attach`。
     */
    private fun installAttachHook(target: Method): XC_MethodHook.Unhook =
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val safe = EzXposed.safeMode
                    try {
                        val context = param.args.firstOrNull() as? Context ?: return
                        dispatch(context)
                    } catch (t: Throwable) {
                        if (safe) {
                            HookDiagnostics.error(TAG, "afterHookedMethod failed", t)
                        } else {
                            throw t
                        }
                    }
                }
            },
        )

    private fun dispatch(context: Context) {
        // attach 入参不保证是 Application，只按原样缓存，由调用方走 appContextOrNull 判断。
        ApplicationAttachRegistry.dispatch(context) { EzXposed.cacheApplicationContextFromLifecycle(it) }
    }
}

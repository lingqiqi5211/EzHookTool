package io.github.lingqiqi5211.ezhooktool.xposed.internal

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/**
 * 统一管理 `Application.attach(Context)` 的 hook 与回调分发。
 *
 * 第一次有调用方注册回调时安装 hook；之后所有注册的回调按注册顺序在 attach after 阶段执行。
 *
 * 注册时如果当前进程已经有可见 application context（即 attach 已经发生过），新注册的回调会立即同步触发一次。
 *
 * 对外只有 [register] 这一个入口。
 */
internal object ApplicationLifecycle {
    private const val TAG = "EzXposed.ApplicationLifecycle"

    private val callbacks = mutableListOf<ApplicationAttachCallback>()

    @Volatile
    private var hookInstalled: Boolean = false

    @Volatile
    private var unhook: XC_MethodHook.Unhook? = null

    fun register(callback: ApplicationAttachCallback) {
        installHookIfNeeded()
        val firePastAttach: Context? = synchronized(callbacks) {
            callbacks += callback
            EzXposed.appContextOrNull
        }
        if (firePastAttach != null) {
            // application 已经 attach 过，本次注册的回调立即补一次，避免错过当前进程。
            invokeSafely(callback, firePastAttach)
        }
    }

    private fun installHookIfNeeded() {
        if (hookInstalled) return
        synchronized(this) {
            if (hookInstalled) return

            val attachMethod = try {
                Application::class.java.getDeclaredMethod("attach", Context::class.java)
            } catch (e: NoSuchMethodException) {
                EzReflect.logger.error(TAG, "Application.attach(Context) not found, cannot install hook", e)
                hookInstalled = true
                return
            }

            // hook 安装后不再 unhook——application 生命周期等价进程生命周期。
            unhook = XposedBridge.hookMethod(attachMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 本回调本身按 EzXposed.safeMode 兜底，避免污染目标 Application.attach。
                    val safe = EzXposed.safeMode
                    try {
                        val context = param.args.firstOrNull() as? Context ?: return
                        dispatch(context)
                    } catch (t: Throwable) {
                        if (safe) {
                            EzReflect.logger.error(TAG, "afterHookedMethod failed", t)
                        } else {
                            throw t
                        }
                    }
                }
            })
            hookInstalled = true
        }
    }

    private fun dispatch(context: Context) {
        EzXposed.cacheApplicationContextFromLifecycle(context)
        val snapshot: List<ApplicationAttachCallback> = synchronized(callbacks) { callbacks.toList() }
        for (callback in snapshot) {
            invokeSafely(callback, context)
        }
    }

    private fun invokeSafely(callback: ApplicationAttachCallback, context: Context) {
        try {
            callback.onApplicationAttached(context)
        } catch (t: Throwable) {
            EzReflect.logger.error(TAG, "ApplicationAttachCallback failed", t)
        }
    }
}

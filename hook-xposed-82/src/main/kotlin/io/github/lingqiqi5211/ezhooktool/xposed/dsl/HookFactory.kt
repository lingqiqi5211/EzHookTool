package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.reflect.Member
import java.util.function.Consumer
import java.util.function.Function

/** Hook 回调签名，接收 [HookParam]。 */
typealias HookCallback = (HookParam) -> Unit

/**
 * Xposed 82 hook DSL 构造器。
 *
 * 通过 [before]、[after]、[replace] 等方法声明行为，最终由 helper API 触发创建。
 *
 * 与 102 的差异（API 设计取舍，不算 bug）：
 *
 * - 每个 [HookFactory] 实例只保留**一对** before / after 回调。重复调用 [before] / [after] / [replace] /
 *   [returnConstant] 会**覆盖**前一次注册，不会叠加。
 * - 没有 `priority` / `id` / `intercept` 等 102 才支持的 hook 元信息——经典 Xposed API 82 在框架层就没有这些能力。
 *
 * 如果你的代码同时支持两个工件，请按 102 的多 stage 思路写时显式包装，
 * 不要假设 82 也会按顺序执行多次 before/after。
 */
class HookFactory internal constructor(
    private val target: Member,
) {
    private var beforeCallback: HookCallback? = null
    private var afterCallback: HookCallback? = null

    /**
     * 注册 before 阶段回调。
     *
     * @param callback 原始方法执行前触发的回调
     */
    fun before(callback: HookCallback) {
        beforeCallback = callback
    }

    /**
     * 注册 Java `Consumer` 形式的 before 阶段回调。
     *
     * @param callback 原始方法执行前触发的 Java 回调
     */
    fun before(callback: Consumer<HookParam>) {
        beforeCallback = { callback.accept(it) }
    }

    /**
     * 注册 after 阶段回调。
     *
     * @param callback 原始方法执行后触发的回调
     */
    fun after(callback: HookCallback) {
        afterCallback = callback
    }

    /**
     * 注册 Java `Consumer` 形式的 after 阶段回调。
     *
     * @param callback 原始方法执行后触发的 Java 回调
     */
    fun after(callback: Consumer<HookParam>) {
        afterCallback = { callback.accept(it) }
    }

    /**
     * 用自定义返回值替换原始实现。
     *
     * @param callback 用于生成替代返回值的回调
     */
    fun replace(callback: (HookParam) -> Any?) {
        beforeCallback = { it.result = callback(it) }
    }

    /**
     * 用 Java `Function` 形式替换原始实现。
     *
     * @param callback 用于生成替代返回值的 Java 回调
     */
    fun replace(callback: Function<HookParam, Any?>) {
        beforeCallback = { it.result = callback.apply(it) }
    }

    /** 中断原始调用并返回 `null`。 */
    fun interrupt() {
        beforeCallback = { it.result = null }
    }

    /**
     * 中断原始调用并返回固定值。
     *
     * @param value 要直接写入的返回值
     */
    fun returnConstant(value: Any?) {
        beforeCallback = { it.result = value }
    }

    internal fun create(): XC_MethodHook.Unhook {
        val before = beforeCallback
        val after = afterCallback
        return XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (before == null) return
                dispatchBefore(before, param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (after == null) return
                dispatchAfter(after, param)
            }
        })
    }

    private fun dispatchBefore(callback: HookCallback, raw: XC_MethodHook.MethodHookParam) {
        if (!EzXposed.safeMode) {
            callback(HookParam(raw))
            return
        }
        // safeMode: 记录 callback 执行前的 result / throwable 快照；callback 抛出时回退到原始调用，
        // 避免半成品的 setResult 让目标 app 接收到错误返回值。
        val savedResult = raw.result
        val savedThrowable = raw.throwable
        try {
            callback(HookParam(raw))
        } catch (t: Throwable) {
            raw.result = savedResult
            raw.throwable = savedThrowable
            EzReflect.logger.error("Hook", "before hook failed for $target; reverted to original call", t)
        }
    }

    private fun dispatchAfter(callback: HookCallback, raw: XC_MethodHook.MethodHookParam) {
        if (!EzXposed.safeMode) {
            callback(HookParam(raw))
            return
        }
        // safeMode: after 阶段原方法已经执行完，捕获 callback 异常不还原 result/throwable，
        // 因为这正是上游方法的真实输出；只记日志。
        try {
            callback(HookParam(raw))
        } catch (t: Throwable) {
            EzReflect.logger.error("Hook", "after hook failed for $target", t)
        }
    }
}

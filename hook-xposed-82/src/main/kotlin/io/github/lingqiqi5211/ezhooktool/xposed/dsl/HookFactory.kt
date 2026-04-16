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
                dispatch("before", before, HookParam(param))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (after == null) return
                dispatch("after", after, HookParam(param))
            }
        })
    }

    private fun dispatch(phase: String, callback: HookCallback, param: HookParam) {
        if (!EzXposed.safeMode) {
            callback(param)
            return
        }
        runCatching { callback(param) }
            .onFailure { EzReflect.logger.error("Hook", "$phase hook failed for $target", it) }
    }
}

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dispatchStages
import java.lang.reflect.Executable
import java.util.function.Consumer
import java.util.function.Function

/** Hook 回调签名。 */
typealias HookCallback = (HookParam) -> Unit

internal sealed interface HookStage {
    data class Before(val callback: HookCallback) : HookStage
    data class After(val callback: HookCallback) : HookStage
    data class Replace(val callback: (HookParam) -> Any?) : HookStage
    data class Intercept(val callback: XposedInterface.Hooker) : HookStage
}

/** libxposed 101 hook DSL 构造器。 */
class HookFactory internal constructor(
    private val target: Executable,
) {
    private val stages = mutableListOf<HookStage>()
    private var priority: Int = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT

    /**
     * 注册 before 回调。
     *
     * @param callback 原始调用前执行的回调
     */
    fun before(callback: HookCallback) {
        stages += HookStage.Before(callback)
    }

    /**
     * 注册 Java `Consumer` 形式的 before 回调。
     *
     * @param callback 原始调用前执行的 Java 回调
     */
    fun before(callback: Consumer<HookParam>) {
        stages += HookStage.Before { callback.accept(it) }
    }

    /**
     * 注册 after 回调。
     *
     * @param callback 原始调用后执行的回调
     */
    fun after(callback: HookCallback) {
        stages += HookStage.After(callback)
    }

    /**
     * 注册 Java `Consumer` 形式的 after 回调。
     *
     * @param callback 原始调用后执行的 Java 回调
     */
    fun after(callback: Consumer<HookParam>) {
        stages += HookStage.After { callback.accept(it) }
    }

    /**
     * 用自定义返回值替换原始实现。
     *
     * @param callback 生成替代返回值的回调
     */
    fun replace(callback: (HookParam) -> Any?) {
        stages += HookStage.Replace(callback)
    }

    /**
     * 用 Java `Function` 形式替换原始实现。
     *
     * @param callback 生成替代返回值的 Java 回调
     */
    fun replace(callback: Function<HookParam, Any?>) {
        stages += HookStage.Replace { callback.apply(it) }
    }

    /**
     * 注册 around 回调，可自行决定是否继续原始调用。
     *
     * @param callback 接收 [XposedInterface.Chain] 的 around 回调
     */
    fun intercept(callback: (XposedInterface.Chain) -> Any?) {
        stages += HookStage.Intercept(XposedInterface.Hooker { callback(it) })
    }

    /**
     * 注册 libxposed 原生 `Hooker` 形式的 around 回调。
     *
     * @param callback libxposed 原生 hooker
     */
    fun intercept(callback: XposedInterface.Hooker) {
        stages += HookStage.Intercept(callback)
    }

    /** 中断原始调用并返回 `null`。 */
    fun interrupt() {
        stages += HookStage.Replace { null }
    }

    /**
     * 中断原始调用并返回固定值。
     *
     * @param value 要返回给调用方的固定值
     */
    fun returnConstant(value: Any?) {
        stages += HookStage.Replace { value }
    }

    /**
     * 设置 hook 优先级。
     *
     * @param priority 数值越大越先执行
     */
    fun priority(priority: Int) {
        this.priority = priority
    }

    /**
     * 设置 libxposed 异常处理模式。
     *
     * @param mode hook 过程中异常的处理策略
     */
    fun exceptionMode(mode: XposedInterface.ExceptionMode) {
        exceptionMode = mode
    }

    internal fun create(): XposedInterface.HookHandle {
        val localStages = stages.toList()
        return EzXposed.base.hook(target)
            .setPriority(priority)
            .setExceptionMode(exceptionMode)
            .intercept(XposedInterface.Hooker { chain ->
                if (!EzXposed.safeMode) {
                    dispatchStages(chain, localStages)
                } else {
                    runCatching { dispatchStages(chain, localStages) }
                        .getOrElse {
                            EzReflect.logger.error("Hook", "hook failed for $target", it)
                            chain.proceed()
                        }
                }
            })
    }
}

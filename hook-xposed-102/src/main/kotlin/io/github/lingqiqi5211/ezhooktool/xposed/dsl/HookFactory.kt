package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import io.github.lingqiqi5211.ezhooktool.xposed.RequiresXposedApi
import io.github.lingqiqi5211.ezhooktool.xposed.common.AfterChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.common.BeforeChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.common.ChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookChain
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookStageException
import io.github.lingqiqi5211.ezhooktool.xposed.common.InterceptChainStage
import io.github.lingqiqi5211.ezhooktool.xposed.common.ReplaceChainStage
import java.lang.reflect.Executable
import java.util.function.Consumer
import java.util.function.Function

/** Hook 回调签名。 */
typealias HookCallback = (HookParam) -> Unit

/** libxposed 102 hook DSL 构造器。 */
class HookFactory internal constructor(
    private val target: Executable,
) {
    private val stages = mutableListOf<ChainStage>()
    private var priority: Int = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.DEFAULT
    private var hookId: String? = null
    private var automaticIdEnabled: Boolean = true

    /**
     * 注册 before 回调。
     *
     * @param callback 原始调用前执行的回调
     */
    fun before(callback: HookCallback) {
        stages += BeforeChainStage(callback)
    }

    /**
     * 注册 Java `Consumer` 形式的 before 回调。
     *
     * @param callback 原始调用前执行的 Java 回调
     */
    fun before(callback: Consumer<HookParam>) {
        stages += BeforeChainStage { callback.accept(it) }
    }

    /**
     * 注册 after 回调。
     *
     * @param callback 原始调用后执行的回调
     */
    fun after(callback: HookCallback) {
        stages += AfterChainStage(callback)
    }

    /**
     * 注册 Java `Consumer` 形式的 after 回调。
     *
     * @param callback 原始调用后执行的 Java 回调
     */
    fun after(callback: Consumer<HookParam>) {
        stages += AfterChainStage { callback.accept(it) }
    }

    /**
     * 用自定义返回值替换原始实现。
     *
     * @param callback 生成替代返回值的回调
     */
    fun replace(callback: (HookParam) -> Any?) {
        stages += ReplaceChainStage(callback)
    }

    /**
     * 用 Java `Function` 形式替换原始实现。
     *
     * @param callback 生成替代返回值的 Java 回调
     */
    fun replace(callback: Function<HookParam, Any?>) {
        stages += ReplaceChainStage { callback.apply(it) }
    }

    /**
     * 注册 around 回调，可自行决定是否继续原始调用。
     *
     * @param callback 接收 [XposedInterface.Chain] 的 around 回调
     */
    fun intercept(callback: (XposedInterface.Chain) -> Any?) {
        stages += InterceptChainStage(callback)
    }

    /**
     * 注册 libxposed 原生 `Hooker` 形式的 around 回调。
     *
     * @param callback libxposed 原生 hooker
     */
    fun intercept(callback: XposedInterface.Hooker) {
        stages += InterceptChainStage { callback.intercept(it) }
    }

    /** 中断原始调用并返回 `null`。 */
    fun interrupt() {
        returnConstant(null)
    }

    /**
     * 中断原始调用并返回固定值。
     *
     * @param value 要返回给调用方的固定值
     */
    fun returnConstant(value: Any?) {
        stages += ReplaceChainStage { value }
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

    /**
     * 为当前 hook 设置底层 hook ID。
     *
     * 同模块、同 executable 下相同 hook ID 的新 hook 会原子替换旧 hook，旧 [XposedInterface.HookHandle] 失效。
     * 传 `null` 表示明确不分配 hook ID，并关闭本库的默认自动聚合 / ID；为热重载保持稳定的 hook ID 请优先
     * 使用 [reloadKey]，避免把一般内部 hook ID 和跨版本契约混为一谈。
     *
     * hook ID 是 libxposed API 102 能力。运行在只实现 API 101 的 framework 上时，传非 `null` 值会在
     * 安装该 hook 时抛出 [IllegalStateException]；传 `null` 与不调用本方法都能正常工作。
     *
     * @param value hook ID，可为 `null`，非空时不得为空白字符串
     */
    @RequiresXposedApi(102)
    fun id(value: String?) {
        require(value == null || value.isNotBlank()) { "hook ID must not be blank." }
        hookId = value
        automaticIdEnabled = value != null
    }

    /**
     * 为 [io.github.lingqiqi5211.ezhooktool.xposed.HotReloadSession] 指定稳定 reloadKey。
     *
     * 同一目标方法（或构造器）在新旧代码中使用相同 reloadKey 时，libxposed 会原子替换旧 hook。
     * 与 [id] 的底层含义相同；这个名称专门用于声明「该 hook ID 是跨版本稳定契约」。
     * 同样要求 framework 实现 libxposed API 102，见 [id]。
     */
    @RequiresXposedApi(102)
    fun reloadKey(value: String) {
        require(value.isNotBlank()) { "reloadKey must not be blank." }
        hookId = value
        automaticIdEnabled = true
    }

    internal fun create(): XposedInterface.HookHandle {
        require(stages.isNotEmpty()) { "No hook callback specified" }
        val hooker = buildHooker(target, stages.toList())
        return EzXposed.installHookWithHotReloadTracking(
            target = target,
            priority = priority,
            exceptionMode = exceptionMode,
            id = hookId,
            automaticIdEnabled = automaticIdEnabled,
            hooker = hooker,
        ) { effectiveId, effectiveHooker ->
            val builder = EzXposed.base.hook(target)
                .setPriority(priority)
                .setExceptionMode(exceptionMode)
            // API 101 的 HookBuilder 没有 setId。只有拿到 ID 时才走这一步——ID 为 null 说明
            // framework 不支持 hook ID，或模块关掉了热重载。
            val builderWithId = if (effectiveId != null) XposedApiCompat.Api102.setId(builder, effectiveId) else builder
            builderWithId.intercept(effectiveHooker)
        }
    }
}

/**
 * 把若干 [ChainStage] 包成单个 [XposedInterface.Hooker]，并在 [EzXposed.safeMode] 打开时保护原始调用。
 *
 * 对外不暴露，供 [HookFactory.create] 和 [replaceHook] 共用，确保替换后的 hook 沿用同样的安全语义。
 */
internal fun buildHooker(
    target: Executable,
    stages: List<ChainStage>,
): XposedInterface.Hooker {
    val hookChain = HookChain(stages)
    return XposedInterface.Hooker { chain ->
        if (!EzXposed.safeMode) {
            hookChain.invoke(chain)
        } else {
            try {
                hookChain.invoke(chain)
            } catch (t: HookStageException) {
                EzReflect.logger.error("Hook", "${t.phase} hook failed for $target", t.cause ?: t)
                t.fallback()
            }
        }
    }
}

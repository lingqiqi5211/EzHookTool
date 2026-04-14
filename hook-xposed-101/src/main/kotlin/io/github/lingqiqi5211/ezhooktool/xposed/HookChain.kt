package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookStage
import java.lang.reflect.Executable

internal class InvocationContext(
    private val chain: XposedInterface.Chain,
) {
    val executable: Executable = chain.executable
    var thisObject: Any? = chain.thisObject
    var args: Array<Any?> = chain.args.toTypedArray()
    var result: Any? = null
    var throwable: Throwable? = null
    var skipped: Boolean = false
    var isAfterStage: Boolean = false

    fun proceedOriginal() {
        if (skipped) return
        try {
            result = if (thisObject == null) chain.proceed(args) else chain.proceedWith(thisObject!!, args)
            throwable = null
        } catch (t: Throwable) {
            result = null
            throwable = t
        }
    }
}

internal fun dispatchStages(chain: XposedInterface.Chain, stages: List<HookStage>): Any? {
    val context = InvocationContext(chain)
    val beforeStages = stages.filterIsInstance<HookStage.Before>()
    val afterStages = stages.filterIsInstance<HookStage.After>()
    val aroundStages = stages.filter { it !is HookStage.Before && it !is HookStage.After }

    for (stage in beforeStages) {
        context.isAfterStage = false
        stage.callback(HookParam(context))
        if (context.skipped) break
    }

    if (!context.skipped) {
        fun proceed(index: Int) {
            if (index >= aroundStages.size) {
                context.proceedOriginal()
                return
            }
            when (val stage = aroundStages[index]) {
                is HookStage.Replace -> {
                    context.isAfterStage = false
                    context.skipped = true
                    context.result = stage.callback(HookParam(context))
                    context.throwable = null
                }
                is HookStage.Intercept -> {
                    context.isAfterStage = false
                    context.result = stage.callback.intercept(chain)
                    context.throwable = null
                }
                else -> proceed(index + 1)
            }
        }
        proceed(0)
    }

    for (stage in afterStages.asReversed()) {
        context.isAfterStage = true
        stage.callback(HookParam(context))
    }

    context.throwable?.let { throw it }
    return context.result
}

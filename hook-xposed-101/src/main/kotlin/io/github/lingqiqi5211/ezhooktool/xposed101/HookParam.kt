package io.github.lingqiqi5211.ezhooktool.xposed101

import java.lang.reflect.Executable

/**
 * libxposed 101 hook 回调参数包装。
 *
 * 统一暴露当前可执行成员、参数、结果和异常状态，便于 before/after/replace 回调使用。
 */
class HookParam internal constructor(
    internal val context: InvocationContext,
) {
    /** 当前正在被 hook 的方法或构造器。 */
    val executable: Executable
        get() = context.executable

    /** 当前实例方法的 `this` 对象；静态方法时通常为 `null`。 */
    val thisObject: Any?
        get() = context.thisObject

    /** 把 [thisObject] 直接转换成目标类型。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> thisObjectAs(): T = thisObject as T

    /** 当前调用的参数数组。 */
    val args: Array<Any?>
        get() = context.args

    /**
     * 按下标读取参数并转换成目标类型。
     *
     * @param index 参数下标
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> argAs(index: Int): T = args[index] as T

    /** 当前调用是否已跳过原始实现。 */
    val isSkipped: Boolean
        get() = context.skipped

    /** 当前回调设置或读取的返回值。 */
    var result: Any?
        get() = context.result
        set(value) {
            if (!context.isAfterStage) context.skipped = true
            context.throwable = null
            context.result = value
        }

    /** 把 [result] 直接转换成目标类型。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> resultAs(): T = result as T

    /** 当前回调设置或读取的异常。 */
    var throwable: Throwable?
        get() = context.throwable
        set(value) {
            if (!context.isAfterStage) context.skipped = true
            context.result = null
            context.throwable = value
        }

    /** 当前调用是否带有异常结果。 */
    val hasThrowable: Boolean
        get() = context.throwable != null
}

package io.github.lingqiqi5211.ezhooktool.xposed.common

import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Executable
import java.lang.reflect.Member

/** Xposed 82 hook 回调参数包装。 */
class HookParam internal constructor(
    /** 原始的 Xposed 参数对象。 */
    @JvmField val raw: XC_MethodHook.MethodHookParam,
) {
    /** 当前正在被 hook 的成员。 */
    val member: Member
        get() = raw.method

    /**
     * 当前正在被 hook 的方法或构造器，便于与 102 的 API 表面统一。
     *
     * 经典 Xposed 82 把方法和构造器都包装为 `Member`，这里在需要时向下转型为 [Executable]。
     */
    val executable: Executable
        get() = raw.method as Executable

    /** 当前实例方法的 `this` 对象；静态方法时为 `null`。 */
    val thisObject: Any?
        get() = raw.thisObject

    /** 与 [thisObject] 相同，便于和上游命名保持一致。 */
    val thisObjectOrNull: Any?
        get() = raw.thisObject

    /** 把 [thisObject] 直接转换成目标类型。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> thisObjectAs(): T = thisObject as T

    /** 当前调用的参数数组。 */
    @Suppress("UNCHECKED_CAST")
    val args: Array<Any?>
        get() = raw.args as Array<Any?>

    /** 按下标读取参数。 */
    fun arg(index: Int): Any? = args[index]

    /** 按下标读取参数并转换成目标类型。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> argAs(index: Int): T = args[index] as T

    /** 当前回调设置或读取的返回值。 */
    var result: Any?
        get() = raw.result
        set(value) {
            raw.result = value
        }

    /** 把 [result] 直接转换成目标类型。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> resultAs(): T = result as T

    /** 当前回调设置或读取的异常。 */
    var throwable: Throwable?
        get() = raw.throwable
        set(value) {
            raw.throwable = value
        }

    /** 当前调用是否带有异常结果。 */
    val hasThrowable: Boolean
        get() = raw.throwable != null

    /** 返回结果；如果存在异常则直接抛出。 */
    fun resultOrThrow(): Any? = raw.resultOrThrowable
}

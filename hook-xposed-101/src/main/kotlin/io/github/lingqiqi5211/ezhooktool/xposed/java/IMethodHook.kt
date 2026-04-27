package io.github.lingqiqi5211.ezhooktool.xposed.java

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam

/**
 * 供 Java 实现的 before/after hook 回调。
 */
abstract class IMethodHook {
    /**
     * 原方法执行前调用。
     *
     * @param param 当前 hook 调用参数，可读取参数、设置返回值或设置异常
     */
    open fun before(param: HookParam) = Unit

    /**
     * 原方法执行后调用。
     *
     * @param param 当前 hook 调用参数，可读取或修改返回值、异常
     */
    open fun after(param: HookParam) = Unit
}

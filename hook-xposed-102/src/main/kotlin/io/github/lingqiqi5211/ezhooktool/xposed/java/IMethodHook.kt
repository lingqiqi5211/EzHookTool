package io.github.lingqiqi5211.ezhooktool.xposed.java

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam

/**
 * 供 Java 实现的 before/after hook 回调。
 */
interface IMethodHook {
    /**
     * 原方法执行前调用。
     *
     * @param param 当前 hook 调用参数，可读取参数、设置返回值或设置异常
     */
    fun before(param: HookParam) {}

    /**
     * 原方法执行后调用。
     *
     * @param param 当前 hook 调用参数，可读取或修改返回值、异常
     */
    fun after(param: HookParam) {}

    /**
     * 设置传递给当前 hook 方法所在对象的额外字段值。
     *
     * 当 hook 的是静态方法（[HookParam.thisObject] 为 `null`）时此默认实现什么都不做，避免 NPE。
     *
     * @param param 当前 hook 调用参数，可读取或修改返回值、异常
     * @param key 额外字段的键
     * @param value 额外字段的值
     */
    fun setObjectExtra(param: HookParam, key: String, value: Any?) {
        val thiz = param.thisObject ?: return
        ExtraFields.setInstanceField(thiz, key, value)
    }

    /**
     * 获取传递给当前 hook 方法所在对象的额外字段值。
     *
     * 当 hook 的是静态方法（[HookParam.thisObject] 为 `null`）时此默认实现返回 `null`。
     *
     * @param param 当前 hook 调用参数，可读取或修改返回值、异常
     * @param key 额外字段的键
     */
    fun getObjectExtra(param: HookParam, key: String): Any? {
        val thiz = param.thisObject ?: return null
        return ExtraFields.getInstanceField(thiz, key)
    }
}

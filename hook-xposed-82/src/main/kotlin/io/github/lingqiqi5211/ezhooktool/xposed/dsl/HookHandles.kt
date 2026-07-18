@file:JvmName("HookHandles")

package io.github.lingqiqi5211.ezhooktool.xposed.dsl

import de.robv.android.xposed.XC_MethodHook

/**
 * 一次性 unhook 一组 [XC_MethodHook.Unhook]。
 *
 * 等价于 `forEach { it.unhook() }`，提供这个别名是为了与 102 模块的同名扩展函数保持一致，
 * 让跨工件的调用代码可以用相同写法。
 */
fun Iterable<XC_MethodHook.Unhook>.unhookAll() {
    forEach { it.unhook() }
}

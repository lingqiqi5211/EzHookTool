// 镜像文件：与 hook-xposed-102/.../java/IReplaceHook.kt 内容必须保持一致。
// 改动时请同步修改另一侧。

package io.github.lingqiqi5211.ezhooktool.xposed.java

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam

/**
 * 供 Java 实现的 replace hook 回调。
 */
fun interface IReplaceHook {
    /**
     * 替换原方法执行结果。
     *
     * @param param 当前 hook 调用参数
     * @return 返回给原调用方的结果
     */
    fun replace(param: HookParam): Any?
}

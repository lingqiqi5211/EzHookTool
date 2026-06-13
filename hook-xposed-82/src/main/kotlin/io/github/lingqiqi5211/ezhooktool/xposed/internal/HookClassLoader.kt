// 镜像文件：与 hook-xposed-102/.../internal/HookClassLoader.kt 内容必须保持一致。
// 改动时请同步修改另一侧。

package io.github.lingqiqi5211.ezhooktool.xposed.internal

import io.github.lingqiqi5211.ezhooktool.core.EzReflect

internal object HookClassLoader {
    fun currentOrDefault(explicit: ClassLoader? = null): ClassLoader =
        explicit ?: EzReflect.safeClassLoader
}

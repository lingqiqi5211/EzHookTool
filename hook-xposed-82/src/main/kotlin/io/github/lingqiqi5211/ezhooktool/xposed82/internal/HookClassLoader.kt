package io.github.lingqiqi5211.ezhooktool.xposed82.internal

import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed82.EzXposed

internal object HookClassLoader {
    fun currentOrDefault(explicit: ClassLoader? = null): ClassLoader {
        explicit?.let { return it }
        if (EzReflect.isInitialized) return EzReflect.classLoader
        return runCatching { EzXposed.classLoader }.getOrElse { ClassLoader.getSystemClassLoader() }
    }
}

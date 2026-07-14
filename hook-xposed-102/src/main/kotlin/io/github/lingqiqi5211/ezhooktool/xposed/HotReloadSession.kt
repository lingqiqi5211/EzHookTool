package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.function.Consumer

/**
 * API 102 的会话式热重载协调器。
 *
 * 把同一代的 hook 安装集中到 [onTargetReady]，并在每个 hook 上使用
 * `reloadKey("...")`。重载时，libxposed 会先原子替换 key 与 executable 都相同的 hook；
 * 本类在所有新 hook 成功安装后，才 unhook 没有被新代码重新声明的旧 hook。
 * 因此不会出现旧实现先被全部摘掉、再等待新实现逐个安装的空窗。
 *
 * 一个 module entry 对应一个 session。不要在异步线程里安装属于该 session 的 hook：只有
 * [onTargetReady] 的同步执行窗口会被记录和收尾。
 */
class HotReloadSession {
    /**
     * 跨代宿主状态与外部回调清理入口。
     *
     * 在旧代码的 [prepare] 中，scope 会先保存状态、再执行已登记的清理动作；在新代码的
     * [restore] 中，状态会在 `onTargetReady` 回调前恢复。
     */
    val scope: HotReloadScope = HotReloadScope()

    private val installedHooks = LinkedHashSet<HookIdentity>()
    private val pendingHooks = LinkedHashSet<HookIdentity>()

    private var prepared = false
    private var restored = false

    /**
     * 注册目标进程就绪后的 hook 安装逻辑。
     *
     * 初次加载和热重载恢复都会调用一次。回调中的所有 EzHookTool DSL hook 必须设置非空的
     * [io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory.reloadKey]；缺 key、重复 key
     * 或安装异常都会抛出 [IllegalStateException]，而不是静默留下无法收尾的旧 hook。
     */
    fun onTargetReady(callback: TargetReadyCallback) {
        EzXposed.onTargetReady {
            EzXposed.withHotReloadSession(this) {
                callback.run()
            }
        }
    }

    /**
     * 在旧 entry 的 `onHotReloading` 中调用。
     *
     * [extra] 与 [EzXposed.handleHotReloading] 的 `extra` 含义相同；它会和 [scope] 中的状态
     * 一起跨 module classloader 传递。传入 module classloader 创建的对象会立即抛异常。
     * 返回 `false` 代表目标进程尚未进入可恢复阶段，调用方应把这个返回值直接交给 framework。
     */
    @JvmOverloads
    fun prepare(
        param: XposedModuleInterface.HotReloadingParam,
        extra: Array<Any?> = emptyArray(),
    ): Boolean {
        check(!prepared) { "HotReloadSession.prepare can only be called once per module entry." }
        CrossGenerationState.requireSafe(extra)
        val scopeState = scope.snapshotState()
        CrossGenerationState.requireSafe(scopeState)
        val savedState = arrayOf<Any?>(
            SAVED_STATE_MAGIC,
            SAVED_STATE_VERSION,
            scopeState,
            extra.copyOf(),
        )
        if (!EzXposed.handleHotReloading(param, savedState)) return false

        prepared = true
        try {
            scope.dispose()
        } finally {
            scope.clearOldGenerationState()
        }
        return true
    }

    /**
     * 在新 entry 的 `onHotReloaded` 中调用。
     *
     * 与旧的 [EzXposed.handleHotReloaded] 不同，此入口不会一开始就 unhook 全部旧 handle：
     * 先严格运行新一代 `onTargetReady` 回调，成功注册的同 key hook 由 libxposed 原子替换；
     * 之后才清理没有重新声明的旧 hook。任一初始化错误会原样抛出，避免把失败伪装成成功。
     *
     * [onExtra] 在 [scope] 状态恢复后、`onTargetReady` 回调前同步调用。
     */
    @JvmOverloads
    fun restore(
        base: XposedInterface,
        param: XposedModuleInterface.HotReloadedParam,
        onExtra: Consumer<Array<Any?>>? = null,
    ): HotReloadResult {
        check(!restored) { "HotReloadSession.restore can only be called once per module entry." }

        // 新 hook 注册前先读出旧 handle 的 identity。成功替换后旧 handle 会失效，届时不应再读它。
        val oldHooks = param.oldHookHandles.map { handle ->
            OldHook(handle, HookIdentity.from(handle.executable, handle.id))
        }
        val unkeyedOldHookCount = oldHooks.count { it.identity == null }
        check(unkeyedOldHookCount == 0) {
            "HotReloadSession cannot safely migrate $unkeyedOldHookCount old hook(s) without a reloadKey. " +
                    "Restart the target process once after adding stable keys to every hook."
        }
        restored = true

        val recovered = EzXposed.withHotReloadSession(this) {
            EzXposed.restoreHotReloaded(
                base = base,
                param = param,
                // 保留到所有新 hook 都成功安装后再统一清理，避免默认全量 unhook 的空窗。
                onOldHooks = Consumer { },
                onExtra = Consumer { extra ->
                    val payload = SavedState.restore(extra)
                    scope.restoreState(payload.scopeState)
                    onExtra?.accept(payload.extra)
                },
                propagateTargetReadyFailure = true,
            )
        }
        check(recovered) {
            "HotReloadSession.restore requires saved state created by HotReloadSession.prepare."
        }

        return removeObsoleteOldHooks(oldHooks)
    }

    /** 仅供 [EzXposed] 在当前线程处于本 session 时调用。 */
    internal fun installHook(
        target: Executable,
        id: String?,
        installer: () -> XposedInterface.HookHandle,
    ): XposedInterface.HookHandle {
        val identity = HookIdentity.from(target, id)
            ?: throw IllegalStateException(
                "HotReloadSession requires HookFactory.reloadKey(\"stable-key\") for ${describe(target)}."
            )
        synchronized(this) {
            check(identity !in installedHooks && identity !in pendingHooks) {
                "Duplicate reloadKey \"${identity.key}\" for ${describe(target)} in one HotReloadSession."
            }
            pendingHooks += identity
        }

        return try {
            installer().also {
                synchronized(this) {
                    pendingHooks -= identity
                    installedHooks += identity
                }
            }
        } catch (t: Throwable) {
            synchronized(this) {
                pendingHooks -= identity
            }
            throw t
        }
    }

    private fun removeObsoleteOldHooks(oldHooks: List<OldHook>): HotReloadResult {
        val currentHooks = synchronized(this) { installedHooks.toSet() }
        var replacedCount = 0
        var removedCount = 0
        val failures = mutableListOf<Throwable>()

        for (oldHook in oldHooks) {
            if (oldHook.identity != null && oldHook.identity in currentHooks) {
                // libxposed 已根据 executable + id 原子替换它；旧 handle 现在无效，不能再 unhook。
                replacedCount++
                continue
            }
            try {
                oldHook.handle.unhook()
                removedCount++
            } catch (t: Throwable) {
                failures += t
            }
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "HotReloadSession could not remove ${failures.size} obsolete old hook(s)."
            ).also { error -> failures.forEach(error::addSuppressed) }
        }
        return HotReloadResult(
            installedHookCount = currentHooks.size,
            atomicallyReplacedHookCount = replacedCount,
            removedOldHookCount = removedCount,
        )
    }

    private data class OldHook(
        val handle: XposedInterface.HookHandle,
        val identity: HookIdentity?,
    )

    private data class SavedState(
        val scopeState: Map<String, Any?>,
        val extra: Array<Any?>,
    ) {
        companion object {
            fun restore(value: Array<Any?>): SavedState {
                val array = value as Array<*>
                check(array.size == 4 && array[0] == SAVED_STATE_MAGIC && array[1] == SAVED_STATE_VERSION) {
                    "HotReloadSession saved state is missing or incompatible."
                }
                val rawScopeState = array[2] as? Map<*, *> ?: throw IllegalStateException(
                    "HotReloadSession saved scope state is invalid."
                )
                val scopeState = LinkedHashMap<String, Any?>()
                for ((key, stateValue) in rawScopeState) {
                    check(key is String && key.isNotBlank()) {
                        "HotReloadSession saved scope state contains an invalid key."
                    }
                    CrossGenerationState.requireSafe(stateValue)
                    scopeState[key] = stateValue
                }
                @Suppress("UNCHECKED_CAST")
                val extra = array[3] as? Array<Any?> ?: throw IllegalStateException(
                    "HotReloadSession saved extra state is invalid."
                )
                CrossGenerationState.requireSafe(extra)
                return SavedState(scopeState, extra)
            }
        }
    }

    private data class HookIdentity(
        val executable: Executable,
        val key: String,
    ) {
        companion object {
            fun from(target: Executable, id: String?): HookIdentity? =
                id?.takeIf(String::isNotBlank)?.let { HookIdentity(target, it) }
        }
    }

    private companion object {
        const val SAVED_STATE_MAGIC = "EzHookTool.HotReloadSession"
        const val SAVED_STATE_VERSION = 1
    }
}

/** 一次成功会话式热重载的收尾结果，可用于模块自己的日志或 UI 反馈。 */
data class HotReloadResult(
    /** 新一代同步安装并被本 session 记录的 hook 数量。 */
    val installedHookCount: Int,
    /** 通过相同 executable + reloadKey 由 libxposed 原子替换的旧 hook 数量。 */
    val atomicallyReplacedHookCount: Int,
    /** 新代码没有重新声明、因而在最后被 unhook 的旧 hook 数量。 */
    val removedOldHookCount: Int,
)

/** 旧代码在热重载前必须执行的外部资源清理回调。 */
fun interface HotReloadCleanup {
    fun run()
}

/**
 * [HotReloadSession] 的跨代状态与外部资源清理容器。
 *
 * 只存放 system / system_server / target app classloader 的对象。模块自己的对象、lambda 或包含它们的
 * 容器不能跨代保存；本类会尽早拒绝常见错误，framework 仍会执行最终校验。
 */
class HotReloadScope {
    private val cleanupCallbacks = mutableListOf<HotReloadCleanup>()
    private val state = LinkedHashMap<String, Any?>()

    /**
     * 登记旧 entry 在热重载前的清理动作，例如注销 listener、receiver 或 binder callback。
     * 清理按后进先出执行，且每一项只执行一次。
     */
    fun onReloading(callback: HotReloadCleanup) {
        synchronized(this) {
            cleanupCallbacks += callback
        }
    }

    /** 保存一个需要交给新 entry 的宿主状态。 */
    fun putState(key: String, value: Any?) {
        require(key.isNotBlank()) { "HotReloadScope state key must not be blank." }
        CrossGenerationState.requireSafe(value)
        synchronized(this) {
            state[key] = value
        }
    }

    /** 读取已恢复的宿主状态；key 不存在时返回 `null`。 */
    fun state(key: String): Any? {
        require(key.isNotBlank()) { "HotReloadScope state key must not be blank." }
        return synchronized(this) { state[key] }
    }

    /** 按 [type] 读取已恢复的宿主状态；类型不匹配时返回 `null`。 */
    fun <T> state(key: String, type: Class<T>): T? = state(key)?.let { value ->
        if (type.isInstance(value)) type.cast(value) else null
    }

    internal fun snapshotState(): Map<String, Any?> = synchronized(this) {
        LinkedHashMap(state)
    }

    internal fun restoreState(savedState: Map<String, Any?>) {
        synchronized(this) {
            state.clear()
            state.putAll(savedState)
        }
    }

    internal fun dispose() {
        val callbacks = synchronized(this) {
            cleanupCallbacks.asReversed().toList().also { cleanupCallbacks.clear() }
        }
        var failure: Throwable? = null
        for (callback in callbacks) {
            try {
                callback.run()
            } catch (t: Throwable) {
                if (failure == null) {
                    failure = t
                } else {
                    failure.addSuppressed(t)
                }
            }
        }
        if (failure != null) {
            throw IllegalStateException("A HotReloadScope cleanup callback failed.", failure)
        }
    }

    internal fun clearOldGenerationState() {
        synchronized(this) {
            state.clear()
        }
    }
}

/** 仅作尽早报错；libxposed 对 saved state 的校验仍是最终准则。 */
private object CrossGenerationState {
    private val moduleClassLoader = HotReloadSession::class.java.classLoader

    fun requireSafe(value: Any?) {
        validate(value, IdentityHashMap())
    }

    private fun validate(value: Any?, seen: IdentityHashMap<Any, Boolean>) {
        if (value == null) return
        if (seen.put(value, true) != null) return
        if (isModuleClassLoader(value.javaClass.classLoader)) {
            reject(value.javaClass.name)
        }
        when (value) {
            is Class<*> -> {
                if (isModuleClassLoader(value.classLoader)) {
                    reject("Class<${value.name}>")
                }
            }
            is ClassLoader -> {
                if (value === moduleClassLoader) {
                    reject(value.javaClass.name)
                }
            }
            is java.lang.reflect.Member -> {
                if (isModuleClassLoader(value.declaringClass.classLoader)) {
                    reject("${value.javaClass.name}<${value.declaringClass.name}>")
                }
            }
            is Array<*> -> value.forEach { validate(it, seen) }
            is Map<*, *> -> value.forEach { (key, item) ->
                validate(key, seen)
                validate(item, seen)
            }
            is Iterable<*> -> value.forEach { validate(it, seen) }
        }
    }

    private fun isModuleClassLoader(classLoader: ClassLoader?): Boolean =
        moduleClassLoader != null && classLoader === moduleClassLoader

    private fun reject(typeName: String): Nothing = throw IllegalArgumentException(
        "Hot reload state must not contain module-classloader object: $typeName"
    )
}

private fun describe(target: Executable): String = buildString {
    append(target.declaringClass.name)
    append('#')
    append(target.name)
    append(target.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name })
}

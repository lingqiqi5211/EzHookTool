package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/**
 * API 102 的批次聚合式热重载协调器。
 *
 * [HotReloadSession] 适合新模块：每条 hook 都显式声明稳定的 `reloadKey(...)`。本类适合已有的大型
 * 规则集：把一次**完整、同步**的目标进程初始化包进 [install]，其中未设置 hook ID 的 EzHookTool DSL
 * hook 会按 `executable + priority + exceptionMode` 聚合成一个带稳定内部 hook ID 的底层 hook。
 *
 * 新 generation 重新执行同一初始化批次时，framework 会原子替换每个聚合后的底层 hook。因此规则可以
 * 在不为每条既有 hook 补写 `reloadKey` 的情况下新增、删除或调整同一目标上的回调。显式调用
 * [io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory.id] 或 `reloadKey(...)` 的 hook 不会被聚合，
 * 仍按官方 `executable + hook ID` 语义原子替换。
 *
 * 使用约束：
 *
 * - 一个 module entry、一个目标进程初始化周期只创建一个 batch，并只调用一次 [install]。
 * - [install] 必须包住所有会注册 hook 的同步初始化代码；异步或初始化完成后才创建的 hook 不属于本批次，
 *   应改为同步安装，或使用默认自动模式 / 显式 `reloadKey` 自行定义收尾边界。
 * - 新 generation 必须先在旧 handle 仍有效时调用 [captureOldHooks]，再执行 [install]，最后调用
 *   [finishHotReload]。不要与同一批 hook 的 [HotReloadSession] 混用。
 * - 本类只管理 EzHookTool API 102 DSL/Java helper 创建的 hook。直接调用 libxposed 或旧 Xposed API 的
 *   hook 不会自动进入批次，应迁移到 helper，或在热重载前主动拒绝。
 *
 * 内部聚合 ID 以 `ezhooktool.batch.v1:` 开头，属于本库保留命名空间。
 */
class HookReloadBatch @JvmOverloads constructor(
    namespace: String,
    private val xposed: XposedInterface = EzXposed.base,
    /** 热重载时要求物理 hook identity 集合不变，避免新增 / 删除 hook 造成不可回滚的半切换。 */
    private val requireStableTopologyOnHotReload: Boolean = false,
) {
    private val namespace = namespace.also {
        require(it.isNotBlank()) { "HookReloadBatch namespace must not be blank." }
    }
    private val groupIdPrefix = "$GROUP_ID_PREFIX${sha256(this.namespace).take(16)}:"
    private val lock = Any()
    private val groups = LinkedHashMap<GroupKey, HookGroup>()
    /** 显式 ID hook 也先延后到 commit，避免初始化回调失败时已发布半代新 hook。 */
    private val explicitHooks = LinkedHashMap<HookIdentity, ExplicitHook>()
    private var state = State.NEW
    private var nextToken = 1L
    private var oldHooks: List<OldHook>? = null
    private var hotReloadFinished = false

    /**
     * 执行一次完整的同步 hook 初始化，并在回调正常返回后一次性提交所有聚合 hook。
     *
     * 回调抛异常或底层 hook 安装失败时异常会原样抛出，batch 会进入失败状态；此后
     * [hotReloadBlockReason] 不会再把该 generation 视为可安全热重载。
     */
    fun install(block: Runnable) {
        synchronized(lock) {
            check(state == State.NEW) {
                "HookReloadBatch.install can only be called once per module entry."
            }
            state = State.INSTALLING
        }

        withActive(this) {
            try {
                block.run()
                commit()
            } catch (t: Throwable) {
                synchronized(lock) {
                    state = State.FAILED
                }
                throw t
            }
        }
    }

    /**
     * 在新 generation 注册 hook 之前拍下旧 handle 的 executable 与 hook ID。
     *
     * 旧 generation 中存在未分配 hook ID 的 handle 时会立即失败：它无法被安全归属到本批次，继续
     * 切换会造成无法验证的行为。首次从旧实现迁移到本类时，应先完整重启一次目标进程。
     */
    fun captureOldHooks(handles: Iterable<XposedInterface.HookHandle>) {
        val snapshots = handles.map { handle ->
            val executable = try {
                handle.executable
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to read an old hook executable before hot reload.", t)
            }
            val hookId = try {
                handle.id
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to read an old hook ID before hot reload.", t)
            }
            OldHook(handle, HookIdentity.from(executable, hookId))
        }
        val unkeyedCount = snapshots.count { it.identity == null }
        check(unkeyedCount == 0) {
            "HookReloadBatch cannot safely migrate $unkeyedCount old hook(s) without a hook ID. " +
                "Restart the target process once after enabling HookReloadBatch."
        }

        synchronized(lock) {
            check(state == State.NEW) {
                "HookReloadBatch.captureOldHooks must run before install."
            }
            check(oldHooks == null) { "HookReloadBatch.captureOldHooks can only be called once." }
            oldHooks = snapshots
        }
    }

    /**
     * 完成新 generation 的旧 handle 收尾。
     *
     * 已由相同内部聚合 ID 或显式 hook ID 原子替换的旧 handle 不再触碰；新代码未继续声明的旧 hook
     * 会在此时才 unhook，避免先清空旧实现再安装新实现的空窗。
     */
    fun finishHotReload(): HookReloadBatchResult {
        val snapshot: List<OldHook>
        val currentGroups: Set<HookIdentity>
        val currentDirectHooks: Set<HookIdentity>
        val logicalHookCount: Int
        synchronized(lock) {
            check(state == State.READY) {
                "HookReloadBatch.finishHotReload requires a successfully committed install."
            }
            check(!hotReloadFinished) {
                "HookReloadBatch.finishHotReload can only be called once per module entry."
            }
            snapshot = oldHooks ?: throw IllegalStateException(
                "HookReloadBatch.finishHotReload requires captureOldHooks before install."
            )
            currentGroups = groups.values
                .filter { it.logicalHooks.isNotEmpty() }
                .mapTo(LinkedHashSet()) { HookIdentity(it.key.executable, it.groupId) }
            currentDirectHooks = LinkedHashSet(explicitHooks.keys)
            logicalHookCount = groups.values.sumOf { it.logicalHooks.size }
        }

        var atomicallyReplaced = 0
        var removed = 0
        val failures = mutableListOf<Throwable>()
        for (oldHook in snapshot) {
            val identity = oldHook.identity ?: continue
            if (identity in currentGroups || identity in currentDirectHooks) {
                // 同 executable + hook ID 的旧 handle 已被 framework 原子替换，不能再访问。
                atomicallyReplaced++
                continue
            }
            try {
                oldHook.handle.unhook()
                removed++
            } catch (t: Throwable) {
                failures += t
            }
        }

        synchronized(lock) {
            hotReloadFinished = true
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "HookReloadBatch could not remove ${failures.size} obsolete old hook(s)."
            ).also { error -> failures.forEach(error::addSuppressed) }
        }
        return HookReloadBatchResult(
            logicalHookCount = logicalHookCount,
            physicalHookCount = currentGroups.size,
            explicitHookCount = currentDirectHooks.size,
            atomicallyReplacedHookCount = atomicallyReplaced,
            removedOldHookCount = removed,
        )
    }

    /**
     * 当前 generation 是否可安全发起下一次热重载；`null` 表示 batch 自身没有发现阻塞条件。
     *
     * 调用方仍应检查 listener、资源替换、已 inflate 的资源和其它非 [XposedInterface.HookHandle]
     * 状态是否可跨代恢复。
     */
    val hotReloadBlockReason: String?
        get() = synchronized(lock) {
            when (state) {
                State.NEW -> "HookReloadBatch has not committed target-process hook initialization."
                State.INSTALLING -> "HookReloadBatch is still registering hooks."
                State.FAILED -> "HookReloadBatch hook initialization failed."
                State.READY -> null
            }
        }

    /** 默认自动流程仅在首次目标初始化时进入 batch。 */
    internal val canStartInstall: Boolean
        get() = synchronized(lock) { state == State.NEW }

    /** 新 generation 是否至少注册了一个由本 batch 管理的逻辑或显式 hook。 */
    internal fun hasManagedHooks(): Boolean = synchronized(lock) {
        state == State.READY && (groups.values.any { it.logicalHooks.isNotEmpty() } || explicitHooks.isNotEmpty())
    }

    /** 仅供 [EzXposed] 的 hook 安装路径调用。 */
    internal fun installHook(
        target: Executable,
        priority: Int,
        exceptionMode: XposedInterface.ExceptionMode,
        hookId: String?,
        automaticIdEnabled: Boolean,
        hooker: XposedInterface.Hooker,
        installer: (String?, XposedInterface.Hooker) -> XposedInterface.HookHandle,
    ): XposedInterface.HookHandle {
        val stableId = hookId?.takeIf(String::isNotBlank)
        if (stableId != null) {
            return installExplicitHook(target, stableId, hooker, installer)
        }

        if (!automaticIdEnabled) {
            synchronized(lock) {
                check(state == State.INSTALLING) {
                    "Hooks with automatic ID disabled can only be registered while HookReloadBatch.install is running."
                }
                throw IllegalStateException(
                    "HookFactory.id(null) cannot participate in a HookReloadBatch. " +
                        "Use a reloadKey or install it outside the automatic batch with a custom onOldHooks callback."
                )
            }
        }

        synchronized(lock) {
            check(state == State.INSTALLING) {
                "Unkeyed hooks can only be registered while HookReloadBatch.install is running."
            }
            val key = GroupKey(target, priority, exceptionMode)
            val group = groups.getOrPut(key) {
                HookGroup(key, groupIdFor(key))
            }
            val token = nextToken++
            group.logicalHooks += LogicalHook(token, hooker)
            return AggregatedHookHandle(this, key, token)
        }
    }

    private fun installExplicitHook(
        target: Executable,
        hookId: String,
        hooker: XposedInterface.Hooker,
        installer: (String?, XposedInterface.Hooker) -> XposedInterface.HookHandle,
    ): XposedInterface.HookHandle = synchronized(lock) {
        check(state == State.INSTALLING) {
            "Explicit hooks must be registered while HookReloadBatch.install is running."
        }
        val identity = HookIdentity(target, hookId)
        check(identity !in explicitHooks) {
            "Duplicate hook ID \"$hookId\" for ${describeTarget(target)} in one HookReloadBatch."
        }
        val token = nextToken++
        explicitHooks[identity] = ExplicitHook(identity, token, hooker, installer)
        DeferredExplicitHookHandle(this, identity, token)
    }

    private fun removeExplicitHook(identity: HookIdentity, token: Long) {
        synchronized(lock) {
            val hook = explicitHooks[identity] ?: return
            if (hook.token != token) return
            when (state) {
                State.INSTALLING -> explicitHooks.remove(identity)
                State.READY -> {
                    hook.physicalHandle?.unhook()
                    explicitHooks.remove(identity)
                }
                State.NEW, State.FAILED -> throw IllegalStateException("HookReloadBatch is no longer active.")
            }
        }
    }

    private fun replaceExplicitHook(
        identity: HookIdentity,
        token: Long,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle = synchronized(lock) {
        val hook = explicitHooks[identity] ?: throw IllegalStateException("Hook handle is no longer valid.")
        check(hook.token == token) { "Hook handle is no longer valid." }
        val replacementToken = nextToken++
        when (state) {
            State.INSTALLING -> {
                hook.hooker = hooker
                hook.token = replacementToken
            }
            State.READY -> {
                hook.physicalHandle = (hook.physicalHandle
                    ?: throw IllegalStateException("Hook handle is no longer valid."))
                    .replaceHook(hooker)
                hook.hooker = hooker
                hook.token = replacementToken
            }
            State.NEW, State.FAILED -> throw IllegalStateException("HookReloadBatch is no longer active.")
        }
        DeferredExplicitHookHandle(this, identity, replacementToken)
    }

    private fun commit() {
        synchronized(lock) {
            check(state == State.INSTALLING) { "HookReloadBatch is not installing hooks." }
            ensureStableTopologyBeforeCommit()
            for (group in groups.values) {
                if (group.logicalHooks.isNotEmpty()) {
                    group.physicalHandle = installPhysicalHook(group)
                }
            }
            for (hook in explicitHooks.values) {
                hook.physicalHandle = hook.installer(hook.identity.hookId, hook.hooker)
            }
            state = State.READY
        }
    }

    /**
     * 默认自动模式宁可拒绝物理 hook 拓扑变化，也不在旧 generation 已冻结后先新增 / 删除一部分 hook。
     * 同一 executable、priority、exceptionMode 组内增删逻辑回调不会改变物理 identity，仍可热重载。
     */
    private fun ensureStableTopologyBeforeCommit() {
        if (!requireStableTopologyOnHotReload) return
        val old = oldHooks ?: return
        val oldIdentities = old.mapNotNullTo(LinkedHashSet()) { it.identity }
        val newIdentities = LinkedHashSet<HookIdentity>().apply {
            groups.values
                .filter { it.logicalHooks.isNotEmpty() }
                .forEach { add(HookIdentity(it.key.executable, it.groupId)) }
            addAll(explicitHooks.keys)
        }
        check(oldIdentities == newIdentities) {
            "Automatic hot reload requires an unchanged physical hook topology. " +
                "Restart the target process after adding or removing a hooked executable, priority/mode group, or explicit hook ID."
        }
    }

    /*
     * Physical hook publication stays centralized in [commit]. Do not install explicit ID hooks in
     * [installHook]: otherwise a later callback failure can leave a partially switched generation.
     */

    private fun removeLogicalHook(key: GroupKey, token: Long) {
        synchronized(lock) {
            val group = groups[key] ?: return
            val index = group.logicalHooks.indexOfFirst { it.token == token }
            if (index < 0) return
            group.logicalHooks.removeAt(index)
            publishMutation(group)
        }
    }

    private fun replaceLogicalHook(
        key: GroupKey,
        token: Long,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle = synchronized(lock) {
        val group = groups[key] ?: throw IllegalStateException("Hook handle is no longer valid.")
        val index = group.logicalHooks.indexOfFirst { it.token == token }
        check(index >= 0) { "Hook handle is no longer valid." }
        val replacementToken = nextToken++
        group.logicalHooks[index] = LogicalHook(replacementToken, hooker)
        publishMutation(group)
        AggregatedHookHandle(this, key, replacementToken)
    }

    private fun publishMutation(group: HookGroup) {
        when (state) {
            State.INSTALLING -> Unit
            State.READY -> {
                if (group.logicalHooks.isEmpty()) {
                    group.physicalHandle?.unhook()
                    groups.remove(group.key)
                } else {
                    group.physicalHandle = installPhysicalHook(group)
                }
            }
            State.NEW, State.FAILED -> throw IllegalStateException("HookReloadBatch is no longer active.")
        }
    }

    private fun installPhysicalHook(group: HookGroup): XposedInterface.HookHandle {
        val hookers = group.logicalHooks.map { it.hooker }
        return xposed.hook(group.key.executable)
            .setPriority(group.key.priority)
            .setExceptionMode(group.key.exceptionMode)
            .setId(group.groupId)
            .intercept(CompositeHooker(hookers))
    }

    private fun groupIdFor(key: GroupKey): String = groupIdPrefix + sha256(
        buildString {
            append(key.executable.declaringClass.name)
            append('#')
            append(key.executable.name)
            append(key.executable.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name })
            append('@')
            append(key.priority)
            append('@')
            append(key.exceptionMode.name)
        },
    )

    private fun describeTarget(target: Executable): String =
        "${target.declaringClass.name}#${target.name}"

    private enum class State {
        NEW,
        INSTALLING,
        READY,
        FAILED,
    }

    private data class GroupKey(
        val executable: Executable,
        val priority: Int,
        val exceptionMode: XposedInterface.ExceptionMode,
    )

    private data class HookIdentity(
        val executable: Executable,
        val hookId: String,
    ) {
        companion object {
            fun from(executable: Executable, hookId: String?): HookIdentity? =
                hookId?.takeIf(String::isNotBlank)?.let { HookIdentity(executable, it) }
        }
    }

    private data class LogicalHook(
        val token: Long,
        val hooker: XposedInterface.Hooker,
    )

    private class ExplicitHook(
        val identity: HookIdentity,
        var token: Long,
        var hooker: XposedInterface.Hooker,
        val installer: (String?, XposedInterface.Hooker) -> XposedInterface.HookHandle,
        var physicalHandle: XposedInterface.HookHandle? = null,
    )

    private class HookGroup(
        val key: GroupKey,
        val groupId: String,
        val logicalHooks: MutableList<LogicalHook> = ArrayList(),
        var physicalHandle: XposedInterface.HookHandle? = null,
    )

    private data class OldHook(
        val handle: XposedInterface.HookHandle,
        val identity: HookIdentity?,
    )

    private class AggregatedHookHandle(
        private val batch: HookReloadBatch,
        private val key: GroupKey,
        private val token: Long,
    ) : XposedInterface.HookHandle {
        override fun getExecutable(): Executable = key.executable

        /** 逻辑 hook 本身未分配显式 ID；内部聚合 ID 不暴露给调用方。 */
        override fun getId(): String? = null

        override fun unhook() {
            batch.removeLogicalHook(key, token)
        }

        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
            batch.replaceLogicalHook(key, token, hooker)
    }

    /** 显式 ID hook 在 install 结束前保持为代理，避免在回调失败时抢先发布。 */
    private class DeferredExplicitHookHandle(
        private val batch: HookReloadBatch,
        private val identity: HookIdentity,
        private val token: Long,
    ) : XposedInterface.HookHandle {
        override fun getExecutable(): Executable = identity.executable

        override fun getId(): String = identity.hookId

        override fun unhook() {
            batch.removeExplicitHook(identity, token)
        }

        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
            batch.replaceExplicitHook(identity, token, hooker)
    }

    private class CompositeHooker(
        private val hookers: List<XposedInterface.Hooker>,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? = dispatch(
            index = 0,
            root = chain,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray(),
        )

        private fun dispatch(
            index: Int,
            root: XposedInterface.Chain,
            thisObject: Any?,
            args: Array<Any?>,
        ): Any? {
            if (index >= hookers.size) {
                return if (thisObject == null) {
                    root.proceed(args)
                } else {
                    root.proceedWith(thisObject, args)
                }
            }
            val next = CompositeChain(this, index + 1, root, thisObject, args)
            return try {
                hookers[index].intercept(next)
            } finally {
                next.close()
            }
        }

        private class CompositeChain(
            private val dispatcher: CompositeHooker,
            private val nextIndex: Int,
            private val root: XposedInterface.Chain,
            private val thisObject: Any?,
            private val args: Array<Any?>,
        ) : XposedInterface.Chain {
            private val ownerThread = Thread.currentThread()
            private var open = true

            override fun getExecutable(): Executable {
                ensureUsable()
                return root.executable
            }

            override fun getThisObject(): Any? {
                ensureUsable()
                return thisObject
            }

            override fun getArgs(): List<Any?> {
                ensureUsable()
                return Collections.unmodifiableList(args.asList())
            }

            override fun getArg(index: Int): Any? {
                ensureUsable()
                return args[index]
            }

            override fun proceed(): Any? {
                ensureUsable()
                return dispatcher.dispatch(nextIndex, root, thisObject, args.copyOf())
            }

            override fun proceed(args: Array<out Any?>): Any? {
                ensureUsable()
                return dispatcher.dispatch(nextIndex, root, thisObject, copyArgs(args))
            }

            override fun proceedWith(thisObject: Any): Any? {
                ensureUsable()
                return dispatcher.dispatch(nextIndex, root, thisObject, args.copyOf())
            }

            override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? {
                ensureUsable()
                return dispatcher.dispatch(nextIndex, root, thisObject, copyArgs(args))
            }

            fun close() {
                open = false
            }

            private fun ensureUsable() {
                check(Thread.currentThread() === ownerThread) { "Chain cannot be shared across threads." }
                check(open) { "Chain cannot be reused after intercept returns." }
            }
        }
    }

    companion object {
        private const val GROUP_ID_PREFIX = "ezhooktool.batch.v1:"
        private val activeBatch = ThreadLocal<HookReloadBatch?>()

        internal fun currentActive(): HookReloadBatch? = activeBatch.get()

        private fun <T> withActive(batch: HookReloadBatch, block: () -> T): T {
            val previous = activeBatch.get()
            activeBatch.set(batch)
            return try {
                block()
            } finally {
                if (previous == null) {
                    activeBatch.remove()
                } else {
                    activeBatch.set(previous)
                }
            }
        }
    }
}

/** 一次批次热重载的注册与收尾统计，可直接用于模块日志或 UI 反馈。 */
data class HookReloadBatchResult(
    /** 新 generation 中聚合的逻辑 hook 数量。 */
    val logicalHookCount: Int,
    /** 新 generation 中实际注册的聚合底层 hook 数量。 */
    val physicalHookCount: Int,
    /** 新 generation 中显式声明 hook ID、未参与聚合的 hook 数量。 */
    val explicitHookCount: Int,
    /** 通过相同 executable + hook ID 由 framework 原子替换的旧 hook 数量。 */
    val atomicallyReplacedHookCount: Int,
    /** 新代码未继续声明、在最后 unhook 的旧 hook 数量。 */
    val removedOldHookCount: Int,
)

private fun copyArgs(args: Array<out Any?>): Array<Any?> = Array(args.size) { args[it] }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

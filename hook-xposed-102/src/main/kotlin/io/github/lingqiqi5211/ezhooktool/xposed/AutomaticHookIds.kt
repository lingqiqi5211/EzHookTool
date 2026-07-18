package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 默认聚合事务之外的兜底 hook ID 分配器。
 *
 * 默认的 [EzXposed.onTargetReady] 同步初始化会使用 [HookReloadBatch] 的稳定聚合 ID。仅当 helper
 * hook 在该事务之外注册、且未显式设置 [io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory.id]
 * 或 `reloadKey(...)` 时，才按目标 executable、priority、exceptionMode 与同一槽位内的注册顺序
 * 分配这个兜底 ID，避免静默创建无法识别的无 ID hook。
 *
 * 这不是跨大规模重排的语义 ID；此类延迟 / 事务外 hook 不属于默认自动热重载的可靠边界，需要
 * 显式 `reloadKey(...)` 或自定义旧 handle 处置。
 */
internal class AutomaticHookIdAllocator(namespace: String) {
    private val namespaceDigest = digest(namespace.ifBlank { "default" }).take(NAMESPACE_DIGEST_LENGTH)
    private val lock = Any()
    private val nextOrdinalBySlot = LinkedHashMap<Slot, Int>()
    /** 每个 executable/priority/mode 组合只计算一次摘要，避免大量规则初始化时重复做 SHA-256。 */
    private val digestBySlot = LinkedHashMap<Slot, String>()

    fun allocate(
        target: Executable,
        priority: Int,
        exceptionMode: XposedInterface.ExceptionMode,
    ): String = synchronized(lock) {
        val slot = Slot(target, priority, exceptionMode)
        val ordinal = nextOrdinalBySlot[slot] ?: 0
        nextOrdinalBySlot[slot] = ordinal + 1
        val slotDigest = digestBySlot.getOrPut(slot) { digest(slot.describe()).take(SLOT_DIGEST_LENGTH) }
        val hookId = "$ID_PREFIX$namespaceDigest:$slotDigest:$ordinal"
        hookId
    }

    private data class Slot(
        val executable: Executable,
        val priority: Int,
        val exceptionMode: XposedInterface.ExceptionMode,
    ) {
        fun describe(): String = buildString {
            append(executable.declaringClass.name)
            append('#')
            append(executable.name)
            append(executable.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name })
            append('@')
            append(priority)
            append('@')
            append(exceptionMode.name)
        }
    }

    private companion object {
        const val ID_PREFIX = "ezhooktool.auto.v1:"
        const val NAMESPACE_DIGEST_LENGTH = 16
        const val SLOT_DIGEST_LENGTH = 24

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

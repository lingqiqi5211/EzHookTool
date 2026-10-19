package io.github.lingqiqi5211.ezhooktool.xposed.internal

import android.content.Context
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachSubscription

/**
 * 82 与 102 共用的 attach 回调登记表。
 *
 * 登记回调与 attach 分发共用一把锁；Context 探针在锁外求值，期间若发生分发则重读，
 * 避免晚注册补发遗漏或同一次 attach 重复执行。回调、logger 与 framework hook 安装都在锁外执行。
 *
 * 登记项只在当前 module generation / classloader 内有效，不进入热重载 saved state。
 */
internal object ApplicationAttachRegistry {
    // 沿用 attach 回调原有的日志 tag，调用方按它过滤。
    private const val TAG = "EzXposed.ApplicationLifecycle"

    /** 一次登记。[callback] 在一次性领取或取消后置空，token 只经登记项间接持有回调。 */
    internal class Entry(
        val persistent: Boolean,
        var callback: ApplicationAttachCallback?,
    ) {
        /** 已经分发过的最新 attach 序号；同一个 attach 不会领取两次。仅持锁时读写。 */
        var deliveredSequence: Long = 0
    }

    internal class Subscription(
        internal val entry: Entry,
    ) : ApplicationAttachSubscription {
        override fun cancel() {
            ApplicationAttachRegistry.cancel(entry)
        }
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var sequence: Long = 0

    /**
     * 登记回调并返回 token。
     *
     * [cachedContext] 在锁外求值：非 `null` 表示 attach 已经发生，本次调用在返回前同步补发一次。
     * 探针抛错时尚未登记，不留下回调。调用方必须在安装 attach hook 失败后先抛出。
     */
    fun register(
        callback: ApplicationAttachCallback,
        persistent: Boolean,
        cachedContext: () -> Context?,
    ): ApplicationAttachSubscription {
        val entry = Entry(persistent, callback)
        var lateCallback: ApplicationAttachCallback? = null
        var lateContext: Context? = null
        while (true) {
            val observedSequence = synchronized(lock) { sequence }
            val cached = cachedContext()
            val registered =
                synchronized(lock) {
                    if (sequence != observedSequence) {
                        false
                    } else {
                        entries += entry
                        if (cached != null) {
                            lateCallback = take(entry)
                            entry.deliveredSequence = sequence
                            lateContext = cached
                        }
                        true
                    }
                }
            if (registered) break
        }
        val subscription = Subscription(entry)
        if (lateCallback != null) invoke(lateCallback, lateContext!!)
        return subscription
    }

    /** 分发一次 attach；[cacheContext] 在锁内执行，写缓存与本次扫描看到的是同一份状态。 */
    fun dispatch(
        context: Context,
        cacheContext: (Context) -> Unit,
    ) {
        val snapshot: List<Entry>
        val currentSequence: Long
        synchronized(lock) {
            sequence += 1
            currentSequence = sequence
            cacheContext(context)
            snapshot = entries.toList()
        }
        for (entry in snapshot) {
            // 快照只留登记项：逐个在锁内领取，取消能阻止尚未领取的调用。
            val callback = synchronized(lock) { claim(entry, currentSequence) } ?: continue
            invoke(callback, context)
        }
    }

    internal fun cancel(entry: Entry) {
        synchronized(lock) {
            entry.callback = null
            entries.remove(entry)
        }
    }

    /** 仅在持锁时调用：取出本次可执行的回调，不检查 attach 序号。 */
    private fun take(entry: Entry): ApplicationAttachCallback? {
        val callback = entry.callback ?: return null
        if (!entry.persistent) {
            entry.callback = null
            entries.remove(entry)
        }
        return callback
    }

    /** 仅在持锁时调用：同一个 attach 序号只领取一次。 */
    private fun claim(
        entry: Entry,
        currentSequence: Long,
    ): ApplicationAttachCallback? {
        if (entry.deliveredSequence == currentSequence) return null
        val callback = take(entry) ?: return null
        entry.deliveredSequence = currentSequence
        return callback
    }

    private fun invoke(
        callback: ApplicationAttachCallback,
        context: Context,
    ) {
        try {
            callback.onApplicationAttached(context)
        } catch (t: Throwable) {
            // 用户 logger 自己抛错时仍要继续调用后续回调。
            HookDiagnostics.error(TAG, "ApplicationAttachCallback failed", t)
        }
    }
}

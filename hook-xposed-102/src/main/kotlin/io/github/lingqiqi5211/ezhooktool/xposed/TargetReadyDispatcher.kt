package io.github.lingqiqi5211.ezhooktool.xposed

/** 当前 generation 首次目标就绪初始化的状态；失败不会自动重试。 */
enum class TargetReadyState {
    NOT_STARTED,
    INITIALIZING,
    SUCCEEDED,
    FAILED,
}

/** 一次性 FIFO 分发；回调不持锁执行，终止后不保留待执行回调。 */
internal class TargetReadyDispatcher {
    private val lock = Any()
    private val pending = ArrayDeque<TargetReadyCallback>()

    @Volatile
    var state: TargetReadyState = TargetReadyState.NOT_STARTED
        private set

    @Volatile
    var failure: Throwable? = null
        private set

    val hasCallbacks: Boolean get() = synchronized(lock) { pending.isNotEmpty() }

    fun reset() =
        synchronized(lock) {
            pending.clear()
            failure = null
            state = TargetReadyState.NOT_STARTED
        }

    /** 返回 true 表示首次分发已完成，由调用者立即执行且不再保留 callback。 */
    fun register(callback: TargetReadyCallback): Boolean =
        synchronized(lock) {
            check(state != TargetReadyState.FAILED) {
                "Target-ready initialization failed; callbacks cannot be retried in this generation."
            }
            if (state == TargetReadyState.SUCCEEDED) return true
            pending.addLast(callback)
            false
        }

    fun start(initialize: () -> Unit = {}): Boolean =
        synchronized(lock) {
            if (state != TargetReadyState.NOT_STARTED) return false
            try {
                initialize()
                state = TargetReadyState.INITIALIZING
                true
            } catch (t: Throwable) {
                fail(t)
                throw t
            }
        }

    fun run(
        propagateFailure: Boolean,
        install: ((Runnable) -> Unit)?,
        reportFailure: (Throwable) -> Unit,
    ) {
        try {
            if (install != null) {
                install(Runnable { drain(failFast = true, reportFailure) })
            } else {
                drain(propagateFailure, reportFailure)
            }
            // 提交期间从其它线程加入的回调不属于已提交 batch，但仍不能丢失或提前宣布完成。
            while (true) {
                synchronized(lock) {
                    if (pending.isEmpty()) {
                        state = if (failure == null) TargetReadyState.SUCCEEDED else TargetReadyState.FAILED
                        return
                    }
                }
                drain(propagateFailure, reportFailure)
            }
        } catch (t: Throwable) {
            synchronized(lock) { fail(t) }
            if (propagateFailure) throw t
            reportFailure(t)
        }
    }

    private fun drain(
        failFast: Boolean,
        reportFailure: (Throwable) -> Unit,
    ) {
        while (true) {
            val callback = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            try {
                callback.run()
            } catch (t: Throwable) {
                if (failFast) throw t
                if (failure == null) failure = t
                reportFailure(t)
            }
        }
    }

    private fun fail(t: Throwable) {
        failure = t
        pending.clear()
        state = TargetReadyState.FAILED
    }
}

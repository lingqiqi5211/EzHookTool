package io.github.lingqiqi5211.ezhooktool.xposed

/** 资源变更前登记撤销动作；仅在整个阶段可逆时执行，失败集中上报。 */
internal class ResourceReloadJournal {
    private val undo = ArrayDeque<() -> Unit>()
    private var irreversible = false

    fun record(action: () -> Unit) {
        undo.addLast(action)
    }

    fun markIrreversible() {
        irreversible = true
    }

    fun rollback() {
        check(!irreversible) {
            "Resource injection used addAssetPath and cannot be rolled back; restart the target process."
        }
        var failure: IllegalStateException? = null
        while (undo.isNotEmpty()) {
            try {
                undo.removeLast().invoke()
            } catch (t: Throwable) {
                val error =
                    failure ?: IllegalStateException(
                        "Failed to restore previous resource loaders; restart the target process.",
                    ).also { failure = it }
                error.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }

    fun clear() {
        undo.clear()
    }
}

package io.github.lingqiqi5211.ezhooktool.xposed

/** 仅描述库能观察到的发布边界，不承诺撤销用户自行执行的 framework 操作。 */
internal class HotReloadAttempt {
    private var irreversible = false

    fun markIrreversible() {
        irreversible = true
    }

    fun recover(
        cause: Throwable,
        rollbackResources: () -> Unit,
        releaseResources: () -> Unit,
    ): Throwable {
        if (!irreversible) {
            try {
                rollbackResources()
            } catch (rollbackFailure: Throwable) {
                if (rollbackFailure !== cause) cause.addSuppressed(rollbackFailure)
                irreversible = true
            }
        }
        try {
            releaseResources()
        } catch (releaseFailure: Throwable) {
            if (releaseFailure !== cause) cause.addSuppressed(releaseFailure)
            irreversible = true
        }
        return if (irreversible) {
            IllegalStateException(
                "Hot reload failed after irreversible or uncertain changes. " +
                    "Restart the target process before continuing; resources were not blindly reverted.",
                cause,
            )
        } else {
            cause
        }
    }
}

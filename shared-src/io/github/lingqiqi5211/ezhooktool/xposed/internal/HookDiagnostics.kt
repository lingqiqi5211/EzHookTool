package io.github.lingqiqi5211.ezhooktool.xposed.internal

import io.github.lingqiqi5211.ezhooktool.core.EzReflect

/** 诊断不能打断已经选定的安全回退，不持有错误或回调。 */
internal object HookDiagnostics {
    fun error(
        tag: String,
        message: String,
        failure: Throwable,
    ) {
        log { EzReflect.logger.error(tag, message, failure) }
    }

    fun warn(tag: String, message: String) {
        log { EzReflect.logger.warn(tag, message) }
    }

    fun debug(tag: String, message: String) {
        log { EzReflect.logger.debug(tag, message) }
    }

    private inline fun log(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // 用户 logger 失败时仍须继续原定回退。
        }
    }
}

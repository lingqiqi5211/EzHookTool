package io.github.lingqiqi5211.ezhooktool.xposed

/**
 * [EzXposed.runOnApplicationAttachOnce] / [EzXposed.subscribeApplicationAttach] 的登记凭据。
 *
 * [cancel] 阻止尚未领取的调用；已领取的调用仍可能在取消后开始或完成。
 * 重复 cancel 无害，取消后的 token 不再强持有回调对象。
 *
 * token 只属于登记它的那个 module generation / classloader；热重载不会把它带到新 generation。
 */
interface ApplicationAttachSubscription {
    fun cancel()
}

package io.github.lingqiqi5211.ezhooktool.xposed

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicReference

/**
 * [awaitMainThread] 的投递失败、超时放弃与异常重抛。真实 looper 上的执行顺序仍由真机验证；
 * 这里只覆盖等待方与主线程任务的交接语义。
 */
class MainThreadAwaitTest {
    @Test
    fun rejectedPostFailsWithoutRunningAction() {
        var ran = false
        val failure =
            assertThrows<IllegalStateException> {
                awaitMainThread(post = { false }, timeoutMillis = 1_000L) { ran = true }
            }
        assertTrue(failure.message!!.contains("rejected"), failure.message)
        assertFalse(ran)
    }

    @Test
    fun actionFailureIsRethrownOnWaitingThread() {
        val cause = IllegalArgumentException("boom")
        val failure =
            assertThrows<IllegalArgumentException> {
                awaitMainThread(post = { it.run(); true }, timeoutMillis = 1_000L) { throw cause }
            }
        assertSame(cause, failure)
    }

    @Test
    fun timeoutSkipsTheLateTask() {
        val lateTask = AtomicReference<Runnable?>()
        var ran = false
        val failure =
            assertThrows<IllegalStateException> {
                awaitMainThread(post = { task -> lateTask.set(task); true }, timeoutMillis = 50L) { ran = true }
            }
        assertTrue(failure.message!!.contains("Timed out"), failure.message)
        lateTask.get()!!.run()
        assertFalse(ran, "a task that arrives after the timeout must not touch resources")
    }

    @Test
    fun interruptionFailsWithoutPoisoningLaterRecovery() {
        val lateTask = AtomicReference<Runnable?>()
        val failure = AtomicReference<Throwable?>()
        val interruptFlag = AtomicReference<Boolean>()
        val waiter =
            Thread {
                try {
                    awaitMainThread(post = { task -> lateTask.set(task); true }, timeoutMillis = 10_000L) {
                        error("must not run")
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                    interruptFlag.set(Thread.currentThread().isInterrupted)
                }
            }
        waiter.start()
        while (lateTask.get() == null) Thread.yield()
        waiter.interrupt()
        waiter.join(5_000L)
        assertFalse(waiter.isAlive)
        val error = failure.get() as? IllegalStateException
        assertTrue(error != null, "interruption must fail with IllegalStateException: ${failure.get()}")
        assertTrue(error!!.cause is InterruptedException, error.cause?.toString())
        assertFalse(interruptFlag.get()!!, "the interrupted thread must be able to dispatch cleanup")

        // 中断被消费：同一线程随后还得调度回滚/提交，不能被残留标记立即打断。
        var recovered = false
        awaitMainThread(post = { it.run(); true }, timeoutMillis = 1_000L) { recovered = true }
        assertTrue(recovered, "cleanup after an interrupted wait must still be dispatched")
    }
}

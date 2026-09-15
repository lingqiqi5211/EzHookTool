package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class HookMutationTest {
    @Test
    fun unknownInitialInstallationDoesNotRollBackResources() {
        val attempt = HotReloadAttempt()
        val field = EzXposed::class.java.getDeclaredField("activeHotReloadAttempt").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val active = field.get(null) as ThreadLocal<HotReloadAttempt>
        active.set(attempt)
        try {
            failPublish = true
            val failure = assertThrows<IllegalStateException> { start() }
            assertTrue(failure.message!!.contains("outcome is unknown"))
            var released = false
            val recovered = attempt.recover(failure, { fail("Must not rollback an uncertain installation") }, { released = true })
            assertSame(failure, recovered.cause)
            assertTrue(released)
        } finally {
            active.remove()
        }
    }

    @Test
    fun concurrentFinishCannotUnhookTheSameOldHandleTwice() {
        XposedApiCompat.resolve(xposed)
        val batch = HookReloadBatch("finish-test", xposed)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val calls = AtomicInteger()
        val old = fake<XposedInterface.HookHandle> { method, _ ->
            when (method.name) {
                "getExecutable" -> target
                "getId" -> "obsolete"
                "unhook" -> {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    null
                }
                else -> error(method.name)
            }
        }
        batch.captureOldHooks(listOf(old))
        batch.install(Runnable {})
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<HookReloadBatchResult> { batch.finishHotReload() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Throwable> { assertThrows<IllegalStateException> { batch.finishHotReload() } }
            assertThrows<TimeoutException> { second.get(100, TimeUnit.MILLISECONDS) }
            resume.countDown()
            assertEquals(1, first.get(5, TimeUnit.SECONDS).removedOldHookCount)
            assertTrue(second.get(5, TimeUnit.SECONDS).message!!.contains("once"))
            assertEquals(1, calls.get())
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private val target = String::class.java.getDeclaredMethod("length")
    private var failPrepare = false
    private var failPublish = false
    private var failUnhook = false
    private var removals = 0
    private var publications = 0
    private lateinit var installed: XposedInterface.Hooker
    private val physical =
        fake<XposedInterface.HookHandle> { method, _ ->
            when (method.name) {
                "getExecutable" -> {
                    target
                }

                "getId" -> {
                    "test"
                }

                "unhook" -> {
                    if (failUnhook) error("unhook failed")
                    removals++
                    null
                }

                else -> {
                    error(method.name)
                }
            }
        }
    private val xposed =
        fakeXposed { method, _ ->
            check(method.name == "hook")
            if (failPrepare) error("builder failed")
            lateinit var builder: XposedInterface.HookBuilder
            builder =
                fake { call, args ->
                    when (call.name) {
                        "setPriority", "setExceptionMode", "setId" -> {
                            builder
                        }

                        "intercept" -> {
                            if (failPublish) error("publication failed")
                            installed = args[0] as XposedInterface.Hooker
                            publications++
                            physical
                        }

                        else -> {
                            error(call.name)
                        }
                    }
                }
            builder
        }

    private fun start(): Pair<HookReloadBatch, XposedInterface.HookHandle> {
        XposedApiCompat.resolve(xposed)
        val batch = HookReloadBatch("test", xposed)
        lateinit var handle: XposedInterface.HookHandle
        batch.install(
            Runnable {
                assertEquals(ResourceHookState.PENDING, batch.resourceHookState)
                handle =
                    batch.installHook(
                        target,
                        50,
                        XposedInterface.ExceptionMode.DEFAULT,
                        null,
                        true,
                        XposedInterface.Hooker { 1 },
                    ) { _, _ -> error("aggregated") }
            },
        )
        assertEquals(ResourceHookState.INSTALLED, batch.resourceHookState)
        return batch to handle
    }

    private fun currentValue(): Any? =
        installed.intercept(
            fake { method, _ ->
                when (method.name) {
                    "getThisObject" -> null
                    "getArgs" -> emptyList<Any?>()
                    "getExecutable" -> target
                    else -> error(method.name)
                }
            },
        )

    @Test
    fun failedPreparationRetainsOldHandleAndPublishedCallback() {
        val (batch, old) = start()
        failPrepare = true
        assertThrows<IllegalStateException> { old.replaceHook { 2 } }
        assertEquals(1, currentValue())
        assertNull(batch.hotReloadBlockReason)
        old.unhook()
        assertEquals(1, removals)
    }

    @Test
    fun successfulReplacementInvalidatesOnlyOldToken() {
        val (_, old) = start()
        val next = old.replaceHook { 2 }
        old.unhook()
        assertEquals(0, removals)
        assertEquals(2, currentValue())
        next.unhook()
        assertEquals(1, removals)
    }

    @Test
    fun unknownPublicationFailureBlocksFurtherMutations() {
        val (batch, old) = start()
        failPublish = true
        val failure = assertThrows<IllegalStateException> { old.replaceHook { 2 } }
        assertTrue(failure.message!!.contains("restart"))
        assertEquals(ResourceHookState.FAILED, batch.resourceHookState)
        assertNotNull(batch.hotReloadBlockReason)
        assertEquals(1, currentValue())
        failPublish = false
        assertThrows<IllegalStateException> { old.replaceHook { 3 } }
        assertEquals(1, publications)
    }

    @Test
    fun failedUnhookDoesNotRemoveLogicalRegistration() {
        val (batch, old) = start()
        failUnhook = true
        assertThrows<IllegalStateException> { old.unhook() }
        assertNotNull(batch.hotReloadBlockReason)
        assertEquals(1, currentValue())
        failUnhook = false
        assertThrows<IllegalStateException> { old.unhook() }
        assertEquals(0, removals)
    }
}

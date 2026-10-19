package io.github.lingqiqi5211.ezhooktool.xposed

import android.app.Application
import android.content.Context
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ApplicationAttachRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 82 与 102 共用同一份 registry 逻辑，本测试在两个 artifact 里各跑一次。 */
class ApplicationAttachRegistryTest {
    private val context: Context = Application()

    @BeforeEach
    fun clearBefore() {
        clearRegistry()
    }

    @AfterEach
    fun clearAfter() {
        clearRegistry()
    }

    @Test
    fun onceEntryFiresOnceAndReleasesCallbackReference() {
        val calls = AtomicInteger()
        val token = ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = false) { null }
        val entry = (token as ApplicationAttachRegistry.Subscription).entry
        assertNotNull(entry.callback)

        ApplicationAttachRegistry.dispatch(context) { }
        ApplicationAttachRegistry.dispatch(context) { }

        assertEquals(1, calls.get())
        assertNull(entry.callback)
    }

    @Test
    fun lateRegistrationFiresOnTheRegisteringThreadAndConsumesOnceEntry() {
        val calls = AtomicInteger()
        val token = ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = false) { context }
        val entry = (token as ApplicationAttachRegistry.Subscription).entry

        assertEquals(1, calls.get())
        assertNull(entry.callback)

        ApplicationAttachRegistry.dispatch(context) { }
        assertEquals(1, calls.get())
    }

    @Test
    fun persistentEntryFiresPerAttachUntilCancelled() {
        val calls = AtomicInteger()
        val token = ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = true) { null }
        val entry = (token as ApplicationAttachRegistry.Subscription).entry

        ApplicationAttachRegistry.dispatch(context) { }
        ApplicationAttachRegistry.dispatch(context) { }
        assertEquals(2, calls.get())
        assertNotNull(entry.callback)

        token.cancel()
        token.cancel()
        assertNull(entry.callback)

        ApplicationAttachRegistry.dispatch(context) { }
        assertEquals(2, calls.get())
    }

    @Test
    fun registrationDuringDispatchFiresExactlyOnce() {
        val calls = AtomicInteger()
        var cached: Context? = null
        ApplicationAttachRegistry.register({
            ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = true) { cached }
        }, persistent = false) { null }

        ApplicationAttachRegistry.dispatch(context) { cached = it }
        assertEquals(1, calls.get())

        ApplicationAttachRegistry.dispatch(context) { cached = it }
        assertEquals(2, calls.get())
    }

    @Test
    fun cancelFromAnEarlierCallbackSkipsEntriesNotYetClaimed() {
        val order = mutableListOf<String>()
        var second: ApplicationAttachSubscription? = null
        ApplicationAttachRegistry.register(
            {
                order += "first"
                second!!.cancel()
            },
            persistent = true,
        ) { null }
        second = ApplicationAttachRegistry.register({ order += "second" }, persistent = true) { null }

        ApplicationAttachRegistry.dispatch(context) { }

        assertEquals(listOf("first"), order)
    }

    @Test
    fun claimedCallbackCompletesEvenIfItCancelsItself() {
        val calls = AtomicInteger()
        val completed = AtomicBoolean(false)
        var token: ApplicationAttachSubscription? = null
        token =
            ApplicationAttachRegistry.register(
                {
                    calls.incrementAndGet()
                    token!!.cancel()
                    completed.set(true)
                },
                persistent = true,
            ) { null }

        ApplicationAttachRegistry.dispatch(context) { }
        ApplicationAttachRegistry.dispatch(context) { }

        assertTrue(completed.get())
        assertEquals(1, calls.get())
    }

    @Test
    fun contextProbeDoesNotBlockDispatchAndRetriesStaleResult() {
        val insideProbe = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val cached = AtomicReference<Context?>()
        val failure = AtomicReference<Throwable>()
        val token = AtomicReference<ApplicationAttachSubscription>()
        val calls = AtomicInteger()
        val probes = AtomicInteger()
        val registrant =
            Thread {
                try {
                    token.set(
                        ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = true) {
                            val result = cached.get()
                            if (probes.incrementAndGet() == 1) {
                                insideProbe.countDown()
                                check(releaseProbe.await(5, TimeUnit.SECONDS))
                            }
                            result
                        },
                    )
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        val dispatchDone = CountDownLatch(1)
        val dispatcher =
            Thread {
                try {
                    ApplicationAttachRegistry.dispatch(context) { cached.set(it) }
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    dispatchDone.countDown()
                }
            }
        registrant.start()
        try {
            assertTrue(insideProbe.await(5, TimeUnit.SECONDS))
            dispatcher.start()
            assertTrue(dispatchDone.await(5, TimeUnit.SECONDS), "Context probe must not hold the registry lock")
        } finally {
            releaseProbe.countDown()
            registrant.join(5_000)
            if (dispatcher.state != Thread.State.NEW) dispatcher.join(5_000)
        }
        assertFalse(registrant.isAlive)
        assertFalse(dispatcher.isAlive)
        assertNull(failure.get())
        assertEquals(2, probes.get())
        assertEquals(1, calls.get())
        ApplicationAttachRegistry.dispatch(context) { cached.set(it) }
        assertEquals(2, calls.get())
        token.get().cancel()
    }

    @Test
    fun concurrentRegistrationAndDispatchFireExactlyOnce() {
        repeat(100) { round ->
            val calls = AtomicInteger()
            val cached = AtomicReference<Context?>(if (round % 2 == 0) context else null)
            val barrier = CyclicBarrier(2)
            val registrant =
                Thread {
                    barrier.await()
                    ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = false) { cached.get() }
                }
            val dispatcher =
                Thread {
                    barrier.await()
                    ApplicationAttachRegistry.dispatch(context) { cached.set(it) }
                }
            registrant.start()
            dispatcher.start()
            registrant.join(5_000)
            dispatcher.join(5_000)

            ApplicationAttachRegistry.dispatch(context) { cached.set(it) }
            assertEquals(1, calls.get(), "round $round")
        }
    }

    @Test
    fun failedContextLookupDoesNotRegisterOrLaterInvokeCallback() {
        for (persistent in listOf(false, true)) {
            val calls = AtomicInteger()
            val failure = IllegalStateException("context lookup failed")
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent) { throw failure }
                }

            assertSame(failure, thrown)
            assertTrue(registryEntries().isEmpty())
            ApplicationAttachRegistry.dispatch(context) { }
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun reentrantDispatchDeliversEachAttachToEveryPersistentEntry() {
        val calls = IntArray(3)
        var nested = false
        repeat(3) { index ->
            ApplicationAttachRegistry.register({
                calls[index]++
                if (index == 0 && !nested) {
                    nested = true
                    ApplicationAttachRegistry.dispatch(context) { }
                }
            }, persistent = true) { null }
        }

        ApplicationAttachRegistry.dispatch(context) { }
        assertEquals(listOf(2, 2, 2), calls.toList())
        ApplicationAttachRegistry.dispatch(context) { }
        assertEquals(listOf(3, 3, 3), calls.toList())
    }

    @Test
    fun concurrentPersistentRegistrationAndDispatchDoNotDoubleDeliver() {
        repeat(100) {
            val calls = AtomicInteger()
            val cached = AtomicReference<Context?>()
            val token = AtomicReference<ApplicationAttachSubscription>()
            val failure = AtomicReference<Throwable>()
            val barrier = CyclicBarrier(2)
            val registrant =
                Thread {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        token.set(ApplicationAttachRegistry.register({ calls.incrementAndGet() }, persistent = true) { cached.get() })
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            val dispatcher =
                Thread {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        ApplicationAttachRegistry.dispatch(context) { cached.set(it) }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            registrant.start()
            dispatcher.start()
            registrant.join(5_000)
            dispatcher.join(5_000)
            assertFalse(registrant.isAlive)
            assertFalse(dispatcher.isAlive)
            assertNull(failure.get())
            try {
                assertEquals(1, calls.get())
                ApplicationAttachRegistry.dispatch(context) { cached.set(it) }
                assertEquals(2, calls.get())
            } finally {
                token.get()?.cancel()
            }
        }
    }

    private fun clearRegistry() {
        registryEntries().clear()
        registryField("sequence").setLong(ApplicationAttachRegistry, 0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun registryEntries() = registryField("entries").get(ApplicationAttachRegistry) as MutableList<Any>

    private fun registryField(name: String) = ApplicationAttachRegistry::class.java.getDeclaredField(name).apply { isAccessible = true }
}

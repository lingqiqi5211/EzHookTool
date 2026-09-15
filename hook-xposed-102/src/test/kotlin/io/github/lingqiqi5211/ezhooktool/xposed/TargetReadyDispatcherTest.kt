package io.github.lingqiqi5211.ezhooktool.xposed

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TargetReadyDispatcherTest {
    @Test
    fun reentrantRegistrationIsFifoAndReleased() {
        val dispatcher = TargetReadyDispatcher()
        val calls = mutableListOf<String>()
        dispatcher.register {
            calls += "A"
            assertEquals(TargetReadyState.INITIALIZING, dispatcher.state)
            assertFalse(dispatcher.register { calls += "C" })
        }
        dispatcher.register { calls += "B" }
        assertTrue(dispatcher.start())
        dispatcher.run(true, { it.run() }) { throw AssertionError(it) }
        assertEquals(listOf("A", "B", "C"), calls)
        assertEquals(TargetReadyState.SUCCEEDED, dispatcher.state)
        assertFalse(dispatcher.hasCallbacks)
        assertFalse(dispatcher.start())
        assertTrue(dispatcher.register { fail("Completed callbacks must not be retained") })
        assertFalse(dispatcher.hasCallbacks)
    }

    @Test
    fun batchFailureIsQueryableAndDoesNotRetryOrRetainCallbacks() {
        val dispatcher = TargetReadyDispatcher()
        val cause = IllegalArgumentException("lookup failed")
        dispatcher.register { throw cause }
        dispatcher.register { fail("Aborted callbacks must not run") }
        dispatcher.start()
        val logged = mutableListOf<Throwable>()
        dispatcher.run(false, { it.run() }, logged::add)
        assertEquals(TargetReadyState.FAILED, dispatcher.state)
        assertSame(cause, dispatcher.failure)
        assertEquals(listOf(cause), logged)
        assertFalse(dispatcher.hasCallbacks)
        assertFalse(dispatcher.start())
        assertThrows<IllegalStateException> { dispatcher.register {} }
    }

    @Test
    fun nonBatchFailureKeepsIsolationButReportsFailedState() {
        val dispatcher = TargetReadyDispatcher()
        val cause = IllegalStateException("A failed")
        var secondRan = false
        dispatcher.register { throw cause }
        dispatcher.register { secondRan = true }
        dispatcher.start()
        dispatcher.run(false, null) {}
        assertTrue(secondRan)
        assertSame(cause, dispatcher.failure)
        assertEquals(TargetReadyState.FAILED, dispatcher.state)
        assertFalse(dispatcher.hasCallbacks)
    }

    @Test
    fun reloadFailurePropagatesAndReleasesQueue() {
        val dispatcher = TargetReadyDispatcher()
        val cause = IllegalStateException("failed")
        dispatcher.register { throw cause }
        dispatcher.register {}
        dispatcher.start()
        assertSame(cause, assertThrows<IllegalStateException> { dispatcher.run(true, null) {} })
        assertFalse(dispatcher.hasCallbacks)
        assertEquals(TargetReadyState.FAILED, dispatcher.state)
    }

    @Test
    fun callbackRegisteredDuringCommitIsNotLost() {
        val dispatcher = TargetReadyDispatcher()
        val calls = mutableListOf<String>()
        dispatcher.register { calls += "first" }
        dispatcher.start()
        dispatcher.run(true, { block ->
            block.run()
            val registration = Thread { dispatcher.register { calls += "during commit" } }
            registration.start()
            registration.join(2000)
            assertFalse(registration.isAlive)
        }) { throw AssertionError(it) }
        assertEquals(listOf("first", "during commit"), calls)
        assertEquals(TargetReadyState.SUCCEEDED, dispatcher.state)
    }

    @Test
    fun newGenerationClearsFailureAndPendingReferences() {
        val dispatcher = TargetReadyDispatcher()
        dispatcher.register { error("fail") }
        dispatcher.start()
        dispatcher.run(false, null) {}
        dispatcher.reset()
        assertNull(dispatcher.failure)
        assertEquals(TargetReadyState.NOT_STARTED, dispatcher.state)
        dispatcher.register { }
        dispatcher.reset()
        assertFalse(dispatcher.hasCallbacks)
        assertTrue(dispatcher.start())
        dispatcher.run(true, null) {}
        assertEquals(TargetReadyState.SUCCEEDED, dispatcher.state)
    }
}

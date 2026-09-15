package io.github.lingqiqi5211.ezhooktool.xposed

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HotReloadAttemptTest {
    @Test
    fun providerCleanupFailurePreservesOriginalFailureAndRequiresRestart() {
        val original = IllegalStateException("hook failed")
        val cleanup = IllegalStateException("provider close failed")
        val attempt = HotReloadAttempt()
        attempt.markIrreversible()
        val result = attempt.recover(original, { error("must not rollback") }, { throw cleanup })
        assertSame(original, result.cause)
        assertTrue(result.message!!.contains("Restart"))
        assertArrayEquals(arrayOf(cleanup), original.suppressed)
    }

    @Test
    fun extraCallbackFailureRollsBackBeforePublication() {
        val attempt = HotReloadAttempt()
        val cause = IllegalArgumentException("extra failed")
        val calls = mutableListOf<String>()
        assertSame(cause, attempt.recover(cause, { calls += "rollback" }, { calls += "release" }))
        assertEquals(listOf("rollback", "release"), calls)
    }

    @Test
    fun partialReplacementOrCustomCleanupNeverBlindlyRollsBackResources() {
        val attempt = HotReloadAttempt()
        attempt.markIrreversible()
        val cause = IllegalStateException("replacement failed")
        var released = false
        val failure = attempt.recover(cause, { fail("Unsafe rollback") }, { released = true })
        assertSame(cause, failure.cause)
        assertTrue(failure.message!!.contains("Restart"))
        assertTrue(released)
    }

    @Test
    fun rollbackFailureKeepsOriginalCauseAndRequiresRestart() {
        val cause = IllegalArgumentException("callback")
        val rollback = IllegalStateException("restore loader")
        var released = false
        val failure = HotReloadAttempt().recover(cause, { throw rollback }, { released = true })
        assertSame(cause, failure.cause)
        assertArrayEquals(arrayOf(rollback), cause.suppressed)
        assertTrue(released)
    }

    @Test
    fun journalRecordsBeforeMutationAndUnwindsInReverseOrder() {
        val journal = ResourceReloadJournal()
        val loaders = mutableListOf("oldA", "oldB")
        journal.record { loaders[0] = "oldA" }
        loaders[0] = "newA"
        journal.record { loaders[1] = "oldB" }
        loaders[1] = "newB"
        journal.rollback()
        assertEquals(listOf("oldA", "oldB"), loaders)
        journal.rollback()
    }

    @Test
    fun journalContinuesOtherResourcesAndReportsEveryFailure() {
        val journal = ResourceReloadJournal()
        val calls = mutableListOf<Int>()
        journal.record {
            calls += 1
            error("first")
        }
        journal.record {
            calls += 2
            error("second")
        }
        val failure = assertThrows<IllegalStateException> { journal.rollback() }
        assertEquals(listOf(2, 1), calls)
        assertEquals(2, failure.suppressed.size)
        journal.clear()
    }

    @Test
    fun failedOldLoaderRestoreMustNotRemoveNewLoader() {
        val journal = ResourceReloadJournal()
        var newLoaderRemoved = false

        fun restoreOld(): Unit = error("add old failed")
        journal.record {
            restoreOld()
            newLoaderRemoved = true
        }
        assertThrows<IllegalStateException> { journal.rollback() }
        assertFalse(newLoaderRemoved)
    }

    @Test
    fun repeatedCleanupThrowableDoesNotSkipRemainingCallbacks() {
        val scope = HotReloadScope()
        val cause = IllegalArgumentException("shared failure")
        var lastCleanupRan = false
        scope.onReloading { lastCleanupRan = true }
        repeat(2) { scope.onReloading { throw cause } }
        val failure = assertThrows<IllegalStateException> { scope.dispose() }
        assertSame(cause, failure.cause)
        assertTrue(lastCleanupRan)
        scope.dispose()
    }

    @Test
    fun legacyInjectionRefusesPartialResourceRollback() {
        val journal = ResourceReloadJournal()
        journal.record { fail("Cannot safely undo mixed legacy and loader injection") }
        journal.markIrreversible()
        assertThrows<IllegalStateException> { journal.rollback() }
        journal.clear()
    }
}

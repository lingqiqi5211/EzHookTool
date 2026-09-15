package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.lingqiqi5211.ezhooktool.core.EzLogger
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.common.CallbackState
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookDiagnostics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CallbackStateTest {
    @Test
    fun unchangedBeforeDoesNotRequestEarlyReturn() {
        val source = arrayOf<Any?>("old")
        val state = CallbackState(source, null, null)
        state.args[0] = "new"
        assertEquals("old", source[0])
        assertFalse(state.outputChanged)
    }

    @Test
    fun explicitNullIsDifferentFromNoOutputChange() {
        val state = CallbackState(emptyArray(), 42, null)
        state.result = null
        assertTrue(state.outputChanged)
        assertNull(state.resultOrThrow())
    }

    @Test
    fun resultAndThrowableAreMutuallyExclusive() {
        val failure = IllegalArgumentException("original")
        val state = CallbackState(emptyArray(), null, failure)
        assertSame(failure, assertThrows<IllegalArgumentException> { state.resultOrThrow() })
        state.result = 7
        assertNull(state.throwable)
        assertEquals(7, state.resultOrThrow())
        state.throwable = failure
        assertNull(state.result)
    }

    @Test
    fun failedAfterCanDiscardIndependentOutput() {
        val originalFailure = IllegalStateException("original")
        val state = CallbackState(emptyArray(), null, originalFailure)
        state.result = 9
        val original = CallbackState(emptyArray(), null, originalFailure)
        assertSame(originalFailure, original.throwable)
        assertFalse(original.outputChanged)
    }

    @Test
    fun loggerFailureDoesNotEscape() {
        val previous = EzReflect.logger
        try {
            EzReflect.logger =
                object : EzLogger {
                    override fun debug(
                        tag: String,
                        msg: String,
                    ) = Unit

                    override fun warn(
                        tag: String,
                        msg: String,
                    ) = Unit

                    override fun error(
                        tag: String,
                        msg: String,
                        t: Throwable?,
                    ) {
                        error("logger failed")
                    }
                }
            assertDoesNotThrow { HookDiagnostics.error("test", "failed", IllegalStateException()) }
        } finally {
            EzReflect.logger = previous
        }
    }
}

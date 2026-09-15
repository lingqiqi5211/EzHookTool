package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HookReloadBatchTest {
    private val executable = String::class.java.getDeclaredMethod("length")
    private val xposed = fakeXposed()
    private val hooker = XposedInterface.Hooker { null }

    @BeforeEach
    fun resolveApi() = XposedApiCompat.resolve(xposed)

    private fun handle(
        id: String,
        unhook: () -> Unit = {},
    ): XposedInterface.HookHandle =
        fake { method, _ ->
            when (method.name) {
                "getExecutable" -> {
                    executable
                }

                "getId" -> {
                    id
                }

                "unhook" -> {
                    unhook()
                    null
                }

                else -> {
                    error(method.name)
                }
            }
        }

    private fun register(
        batch: HookReloadBatch,
        id: String,
        installer: () -> XposedInterface.HookHandle,
    ) {
        batch.installHook(executable, 50, XposedInterface.ExceptionMode.DEFAULT, id, true, hooker) { _, _ -> installer() }
    }

    @Test
    fun obsoleteCleanupFailureBlocksFutureReloadsAndAttemptsAllHandles() {
        val batch = HookReloadBatch("test", xposed)
        var attempted = 0
        val cause = IllegalStateException("unhook failed")
        batch.captureOldHooks(
            listOf(
                handle("old-a") {
                    attempted++
                    throw cause
                },
                handle("old-b") { attempted++ },
            ),
        )
        batch.install(Runnable {})
        val failure = assertThrows<IllegalStateException> { batch.finishHotReload() }
        assertEquals(2, attempted)
        assertSame(cause, failure.suppressed.single())
        assertTrue(failure.message!!.contains("restart"))
        assertNotNull(batch.hotReloadBlockReason)
        assertThrows<IllegalStateException> { batch.finishHotReload() }
    }

    @Test
    fun callbacksFailBeforeAnyPhysicalHookIsPublished() {
        val batch = HookReloadBatch("test", xposed)
        batch.captureOldHooks(emptyList())
        val cause = IllegalStateException("lookup failed")
        assertSame(
            cause,
            assertThrows<IllegalStateException> {
                batch.install(
                    Runnable {
                        register(batch, "new") { fail("Must remain deferred") }
                        throw cause
                    },
                )
            },
        )
        assertNotNull(batch.hotReloadBlockReason)
    }

    @Test
    fun failedNewInstallationRollsBackEarlierAdditions() {
        val batch = HookReloadBatch("test", xposed)
        batch.captureOldHooks(emptyList())
        var removed = false
        val cause = IllegalStateException("install failed")
        assertSame(
            cause,
            assertThrows<IllegalStateException> {
                batch.install(
                    Runnable {
                        register(batch, "new-a") { handle("new-a") { removed = true } }
                        register(batch, "new-b") { throw cause }
                    },
                )
            },
        )
        assertTrue(removed)
        assertNotNull(batch.hotReloadBlockReason)
    }

    @Test
    fun partialReplacementReportsRestartWithoutTouchingReplacedOldHandles() {
        val batch = HookReloadBatch("test", xposed)
        batch.captureOldHooks(listOf(handle("a") { fail("Old handle is invalid") }, handle("b")))
        val cause = IllegalStateException("second replacement failed")
        val failure =
            assertThrows<IllegalStateException> {
                batch.install(
                    Runnable {
                        register(batch, "a") { handle("a") }
                        register(batch, "b") { throw cause }
                    },
                )
            }
        assertSame(cause, failure.cause)
        assertTrue(failure.message!!.contains("restart"))
        assertNotNull(batch.hotReloadBlockReason)
    }
}

package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.IdentityHashMap

/**
 * [HotReloadSession.prepare] 交给 framework 的 saved state 只描述新一代可重建的状态：
 * 不包含旧代的 cleanup 回调或模块对象，宿主状态是脱离旧 scope 的副本。
 */
class HotReloadSavedStateTest {
    private val fields =
        listOf(
            "base",
            "targetSnapshot",
            "automaticHookBatch",
            "automaticHookIds",
            "hotReloadFailure",
            "currentHotReloadParam",
        )
    private lateinit var original: Map<String, Any?>
    private lateinit var compatSnapshot: XposedApiCompatSnapshot

    private fun field(name: String) = EzXposed::class.java.getDeclaredField(name).apply { isAccessible = true }

    @BeforeEach
    fun prepareSuccessfulTargetReady() {
        compatSnapshot = XposedApiCompatSnapshot()
        original = fields.associateWith { field(it).get(null) }
        XposedApiCompat.resolve(fakeXposed())
        EzXposed.base = fakeXposed()
        field("hotReloadFailure").set(null, null)
        field("automaticHookBatch").set(null, null)
        field("targetSnapshot").set(null, TargetSnapshot("pkg", "proc", javaClass.classLoader!!, null, false))
        val dispatcher = field("targetReadyDispatcher").get(null) as TargetReadyDispatcher
        dispatcher.reset()
        dispatcher.start()
        dispatcher.run(false, null) {}
        assertEquals(TargetReadyState.SUCCEEDED, EzXposed.targetReadyState)
    }

    @AfterEach
    fun restoreTargetReady() {
        if (::compatSnapshot.isInitialized) compatSnapshot.close()
        if (::original.isInitialized) original.forEach { (name, value) -> field(name).set(null, value) }
        (field("targetReadyDispatcher").get(null) as TargetReadyDispatcher).reset()
    }

    @Test
    fun preparedSavedStateDropsCleanupCallbacksAndDetachesHostState() {
        var cleanupRuns = 0
        val session = HotReloadSession()
        val hostState = arrayListOf("first")
        val cleanup = HotReloadCleanup { cleanupRuns++ }
        session.scope.putState("host", hostState)
        session.scope.onReloading(cleanup)

        val extra = arrayOf<Any?>("business")
        var handedOut: Array<Any?>? = null
        val param = hotReloadingParam { handedOut = it }
        assertTrue(session.prepare(param, extra))

        val payload = handedOut ?: fail("prepare must hand saved state to the framework")
        // handleHotReloading 把它作为 extra 放进 TargetSnapshot 数组的第 8 位。
        val sessionFrame = payload[7] as Array<*>
        assertEquals(4, sessionFrame.size)
        val savedScopeState = sessionFrame[2] as Map<*, *>
        assertEquals(listOf("first"), savedScopeState["host"])
        assertNotSame(hostState, savedScopeState["host"])
        assertNotSame(extra, sessionFrame[3])
        assertEquals(extra.toList(), (sessionFrame[3] as Array<*>).toList())

        assertFalse(
            containsIdentity(payload, cleanup),
            "saved state must not carry the old generation's cleanup callback",
        )
        assertFalse(containsIdentity(payload, this), "saved state must not carry module objects")

        // 交棒后旧 scope 不保留宿主状态，cleanup 恰好执行一次。
        assertNull(session.scope.state("host"))
        assertEquals(1, cleanupRuns)

        // 已交棒的 scope 不再接收新状态/回调，也不会因此重跑 cleanup。
        assertThrows<IllegalStateException> { session.scope.putState("late", 1) }
        assertThrows<IllegalStateException> { session.scope.onReloading { cleanupRuns++ } }
        assertEquals(1, cleanupRuns)
    }

    @Test
    fun moduleObjectsAreRejectedBeforeTheFrameworkReceivesSavedState() {
        val session = HotReloadSession()
        val moduleCallback = Runnable { }
        var handouts = 0
        val param = hotReloadingParam { handouts++ }

        val failure =
            assertThrows<IllegalArgumentException> {
                session.prepare(param, arrayOf<Any?>(moduleCallback))
            }
        assertTrue(failure.message!!.contains("module-classloader"), failure.message)
        assertEquals(0, handouts, "framework must never receive a saved state holding module objects")

        // 准备窗口失败后 scope 重新开放：下一次 prepare 仍是干净的第一次尝试。
        session.scope.putState("retry", "ok")
        assertTrue(session.prepare(param))
        assertEquals(1, handouts)
    }

    @Test
    fun restoredScopeKeepsStateIsolatedAndCanPrepareTheNextGeneration() {
        val firstHostState = arrayListOf("first")
        val first = HotReloadScope()
        first.putState("host", firstHostState)
        val savedForSecond = first.beginPrepare()
        first.sealAfterPrepare()
        first.clearOldGenerationState()

        // 新一代按 SavedState.restore 的复制语义恢复（该方法私有，这里用它的快照对象）。
        val second = HotReloadScope()
        second.restoreState(CrossGenerationState.snapshotState(savedForSecond))
        second.putState("derived", arrayListOf("second"))
        var secondCleanupRuns = 0
        second.onReloading { secondCleanupRuns++ }

        val savedForThird = second.beginPrepare()
        assertEquals("first", (savedForThird["host"] as List<*>)[0])
        assertNotSame(firstHostState, savedForThird["host"])
        @Suppress("UNCHECKED_CAST")
        (savedForThird["host"] as MutableList<Any?>).add("mutated")
        assertEquals(listOf("first"), firstHostState)
        assertEquals("first", (second.state("host") as List<*>)[0])

        assertThrows<IllegalStateException> { second.putState("whilePreparing", "blocked") }
        // 本代准备失败后重新接收状态，不需要重建 scope。
        second.prepareFailed()
        second.putState("recovered", "value")
        assertEquals("value", second.state("recovered"))
        second.dispose()
        assertEquals(1, secondCleanupRuns)
    }

    private fun hotReloadingParam(onSaved: (Array<Any?>) -> Unit): XposedModuleInterface.HotReloadingParam =
        fake { method, args ->
            when (method.name) {
                "setSavedInstanceState" -> {
                    @Suppress("UNCHECKED_CAST")
                    onSaved(args[0] as Array<Any?>)
                    null
                }

                else -> {
                    error("Unexpected HotReloadingParam call: ${method.name}")
                }
            }
        }

    private fun containsIdentity(
        root: Any?,
        target: Any,
    ): Boolean {
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun walk(value: Any?): Boolean {
            if (value == null) return false
            if (value === target) return true
            if (!visited.add(value)) return false
            return when (value) {
                is Array<*> -> value.any(::walk)
                is Map<*, *> -> value.entries.any { walk(it.key) || walk(it.value) }
                is Iterable<*> -> value.any(::walk)
                else -> false
            }
        }
        return walk(root)
    }
}

package io.github.lingqiqi5211.ezhooktool.xposed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HotReloadStateTest {
    @Test
    fun supportedContainersAndArraysAreCopiedIndependently() {
        val nested = arrayListOf<Any?>("before", intArrayOf(1, 2), arrayOf("value"))
        val copied = CrossGenerationState.snapshotArray(arrayOf(nested))

        nested[0] = "after"
        (nested[1] as IntArray)[0] = 9
        (nested[2] as Array<String>)[0] = "changed"

        val copiedNested = copied[0] as List<*>
        assertEquals("before", copiedNested[0])
        assertEquals(1, (copiedNested[1] as IntArray)[0])
        assertEquals("value", (copiedNested[2] as Array<*>)[0])
        assertNotSame(nested, copiedNested)
        assertNotSame(nested[1], copiedNested[1])
        assertNotSame(nested[2], copiedNested[2])
    }

    @Test
    fun arbitraryHostObjectUsesCompatibilityBypass() {
        val hostObject = StringBuilder("before")
        val copied = CrossGenerationState.snapshotArray(arrayOf(hostObject))

        assertSame(hostObject, copied[0])
        hostObject.append(" after")
        assertEquals("before after", (copied[0] as StringBuilder).toString())
    }

    @Test
    fun moduleObjectsAndChildLoadersCannotRetainTheOldGeneration() {
        assertThrows(IllegalArgumentException::class.java) { CrossGenerationState.snapshot(this) }
        assertThrows(IllegalArgumentException::class.java) { CrossGenerationState.snapshot(javaClass) }
        assertThrows(IllegalArgumentException::class.java) { CrossGenerationState.snapshot(emptyArray<HotReloadStateTest>()) }
        java.net.URLClassLoader(emptyArray(), javaClass.classLoader).use { child ->
            assertThrows(IllegalArgumentException::class.java) { CrossGenerationState.snapshot(child) }
        }
    }

    @Test
    fun identityMapKeepsDistinctEqualKeysAndSharedAliases() {
        val first = String(charArrayOf('k'))
        val second = String(charArrayOf('k'))
        val source = java.util.IdentityHashMap<String, Any?>()
        val value = arrayListOf("value")
        source[first] = value
        source[second] = value
        val copy = CrossGenerationState.snapshot(source) as Map<*, *>
        assertEquals(2, copy.size)
        assertNotSame(value, copy[first])
        assertSame(copy[first], copy[second])
    }

    @Test
    fun frameworkResourceChannelRemainsOpaqueToBusinessSnapshot() {
        val snapshot = TargetSnapshot("pkg", "process", javaClass.classLoader!!, null, false)
        val business = arrayOf<Any?>(arrayListOf("before"))
        val resources = Any()
        val saved = snapshot.toCrossGenArray(CrossGenerationState.snapshotArray(business), resources)

        assertSame(resources, TargetSnapshot.restoreResources(saved))
        assertNotSame(business, TargetSnapshot.restoreExtra(saved))
    }

    @Test
    fun snapshotRejectsExcessiveSizeAndDepth() {
        assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshot(IntArray(CrossGenerationState.MAX_VALUES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshot(arrayOfNulls<Any?>(CrossGenerationState.MAX_VALUES + 1))
        }
        val repeated = ArrayList<Any?>(CrossGenerationState.MAX_VALUES + 1)
        repeat(CrossGenerationState.MAX_VALUES + 1) { repeated += null }
        assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshot(repeated)
        }

        var nested: Any = "leaf"
        repeat(CrossGenerationState.MAX_DEPTH + 1) {
            nested = arrayOf(nested)
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshot(nested)
        }
        assertTrue(failure.message!!.contains("depth"))
    }

    @Test
    fun objectArrayKeepsRuntimeComponentTypeAndRejectsIncompatibleContainerCopy() {
        val source = arrayOf(arrayOf("value"))
        val copied = CrossGenerationState.snapshotArray(source)
        assertEquals(source.javaClass, copied.javaClass)
        assertEquals(arrayOf("value").javaClass, copied[0]!!.javaClass)

        class CustomList : ArrayList<String>()
        assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshotArray(arrayOf(CustomList()))
        }
    }

    @Test
    fun cyclicContainersAreRejectedBeforeHashingOrRecursing() {
        val cyclicList = arrayListOf<Any?>()
        cyclicList.add(cyclicList)
        val map = java.util.IdentityHashMap<Any?, Any?>()
        map[cyclicList] = "value"
        assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshot(map)
        }

        val cyclicSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        cyclicSet.add(cyclicSet)
        assertThrows(IllegalArgumentException::class.java) {
            CrossGenerationState.snapshot(cyclicSet)
        }

        assertThrows(IllegalArgumentException::class.java) { CrossGenerationState.snapshot(cyclicList) }
        val array = arrayOfNulls<Any?>(1)
        array[0] = array
        assertThrows(IllegalArgumentException::class.java) { CrossGenerationState.snapshot(array) }
    }

    @Test
    fun scopeSnapshotSurvivesOldGenerationCleanup() {
        val scope = HotReloadScope()
        val state = arrayListOf("before")
        scope.putState("values", state)

        val snapshot = scope.snapshotState()
        scope.clearOldGenerationState()
        state += "after"

        assertEquals(listOf("before"), snapshot["values"])
    }

    @Test
    fun sealedScopeRejectsLaterStateAndCleanupRegistration() {
        val scope = HotReloadScope()
        scope.sealAfterPrepare()

        assertThrows(IllegalStateException::class.java) { scope.putState("late", "value") }
        assertThrows(IllegalStateException::class.java) { scope.onReloading {} }
    }

    @Test
    fun failedPrepareReopensScopeAfterThePreparationWindow() {
        val scope = HotReloadScope()

        scope.putState("first", "value")
        scope.beginPrepare()
        assertThrows(IllegalStateException::class.java) { scope.putState("during", "value") }
        assertThrows(IllegalStateException::class.java) { scope.onReloading {} }

        scope.prepareFailed()
        scope.putState("second", "value")
        scope.onReloading {}
        assertEquals("value", scope.state("second"))
    }
}

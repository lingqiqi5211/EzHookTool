package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CacheStatsTest {
    class Target {
        fun work() = Unit
    }

    private val previousCacheEnabled = EzReflect.cacheEnabled

    @AfterEach
    fun restore() {
        EzReflect.cacheEnabled = previousCacheEnabled
        EzReflect.clearCache()
    }

    @Test
    fun stateCountsHitsMissesEvictionsAndOversizeSkips() {
        val state = ReflectionState(capacity = 2, maxResultSize = 1)
        val owner = Any()

        assertNull(state.get(owner, ReflectCacheBucket.METHOD, "missing"))
        assertEquals(1L, state.stats().misses)
        assertEquals(0L, state.stats().hits)

        state.put(owner, ReflectCacheBucket.METHOD, "a", 1)
        state.put(owner, ReflectCacheBucket.METHOD, "b", 2)
        assertEquals(1, state.get(owner, ReflectCacheBucket.METHOD, "a"))
        assertEquals(1L, state.stats().hits)

        state.put(owner, ReflectCacheBucket.METHOD, "c", 3)
        val afterEviction = state.stats()
        assertEquals(2, afterEviction.entries)
        assertEquals(2, afterEviction.capacity)
        assertEquals(1L, afterEviction.evictions)

        state.put(owner, null, "list", listOf(1, 2))
        assertEquals(1L, state.stats().skippedOversizeResults)
        assertNull(state.get(owner, null, "list"))

        state.retire()
        assertEquals(0, state.stats().entries)
    }

    @Test
    fun snapshotFollowsStateGenerationAndResetsWithIt() {
        EzReflect.cacheEnabled = true
        EzReflect.clearCache()
        val before = EzReflect.cacheStats
        assertEquals(0, before.entries)

        findMethod(Target::class.java) { name("work") }
        findMethod(Target::class.java) { name("work") }
        val used = EzReflect.cacheStats
        assertEquals(before.generation, used.generation)
        assertTrue(used.hits >= 1L)
        assertTrue(used.misses >= 1L)
        assertTrue(used.entries >= 1)
        assertEquals(4096, used.capacity)

        EzReflect.clearCache()
        val cleared = EzReflect.cacheStats
        assertEquals(before.generation + 1, cleared.generation)
        assertEquals(0, cleared.entries)
        assertEquals(0L, cleared.hits)
        assertEquals(0L, cleared.misses)
        assertEquals(0L, cleared.evictions)
    }
}

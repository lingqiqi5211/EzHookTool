package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReflectionContextTest {
    class Target {
        fun valueOld() = Unit

        fun valueNew() = Unit
    }

    @Test
    fun totalCapacityAndResultLimitApplyAcrossOwnersAndBuckets() {
        val state = ReflectionState(capacity = 3, maxResultSize = 2)
        val first = Any()
        val second = Any()
        state.put(first, ReflectCacheBucket.METHOD, "a", 1)
        state.put(second, null, "b", 2)
        state.put(first, ReflectCacheBucket.FIELD, "c", 3)
        assertEquals(1, state.get(first, ReflectCacheBucket.METHOD, "a"))
        state.put(second, ReflectCacheBucket.CONSTRUCTOR, "d", 4)
        assertEquals(3, state.size)
        assertNull(state.get(second, null, "b"))
        state.put(first, null, "large", listOf(1, 2, 3))
        assertNull(state.get(first, null, "large"))
        state.retire()
        state.put(first, null, "late", 5)
        assertEquals(0, state.size)
    }

    @Test
    fun ownersUseIdentityNotEquality() {
        data class Owner(
            val name: String,
        )
        val state = ReflectionState()
        val first = Owner("same")
        val second = Owner("same")
        state.put(first, null, "key", 1)
        state.put(second, null, "key", 2)
        assertEquals(1, state.get(first, null, "key"))
        assertEquals(2, state.get(second, null, "key"))
    }

    @Test
    fun nestedQueriesKeepSnapshotAndThreadStateIsRemovedAfterFailure() {
        val context = ReflectionContext()
        val previous = context.state
        assertThrows<IllegalStateException> {
            context.query {
                context.update { it.copy(cacheEnabled = false) }
                context.query { assertSame(previous, context.state) }
                previous.put(Target::class.java, null, "late", 1)
                assertEquals(0, previous.size)
                error("callback failed")
            }
        }
        assertNull(context.active.get())
        assertNotSame(previous, context.state)
        assertFalse(context.state.cacheEnabled)
    }

    @Test
    fun queryCapturesConfigurationBeforeRunningDsl() {
        val previous = EzReflect.memberResolver
        val owner = Target::class.java
        val oldMethod = owner.getDeclaredMethod("valueOld")
        val newMethod = owner.getDeclaredMethod("valueNew")
        val oldResolver =
            object : MemberResolver by DefaultMemberResolver {
                override fun methodsOf(clz: Class<*>) = arrayOf(oldMethod)
            }
        val newResolver =
            object : MemberResolver by DefaultMemberResolver {
                override fun methodsOf(clz: Class<*>) = arrayOf(newMethod)
            }
        try {
            EzReflect.memberResolver = oldResolver
            val result =
                findMethod(owner) {
                    EzReflect.memberResolver = newResolver
                    assertSame(oldResolver, EzReflect.memberResolver)
                    nameStartsWith("value")
                }
            assertEquals(oldMethod, result)
            assertSame(newResolver, EzReflect.memberResolver)
            assertEquals(newMethod, findMethod(owner) { nameStartsWith("value") })
        } finally {
            EzReflect.memberResolver = previous
        }
    }

    @Test
    fun defaultLoaderIsResolvedInsideTheCapturedState() {
        val previousLoader = EzReflect.classLoader
        val previousInitialized = EzReflect.isInitialized
        val previousResolver = EzReflect.classResolver
        val first = object : ClassLoader(Target::class.java.classLoader) {}
        val second = object : ClassLoader(Target::class.java.classLoader) {}
        var observed: ClassLoader? = null
        try {
            EzReflect.init(first)
            EzReflect.classResolver =
                object : ClassResolver {
                    override fun classNamesOf(classLoader: ClassLoader): Sequence<String> {
                        observed = classLoader
                        return sequenceOf(Target::class.java.name)
                    }
                }
            assertEquals(
                Target::class.java,
                findClassIf {
                    EzReflect.init(second)
                    name(Target::class.java.name)
                },
            )
            assertSame(first, observed)
            assertSame(second, EzReflect.classLoader)
            findClassIf { name(Target::class.java.name) }
            assertSame(second, observed)
        } finally {
            EzReflect.classResolver = previousResolver
            if (previousInitialized) EzReflect.init(previousLoader) else EzReflect.reset()
        }
    }

    @Test
    fun resolverChangeCannotBeRepopulatedByOldInFlightQuery() {
        val previous = EzReflect.memberResolver
        val previousCache = EzReflect.cacheEnabled
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val owner = Target::class.java
        val oldMethod = owner.getDeclaredMethod("valueOld")
        val newMethod = owner.getDeclaredMethod("valueNew")
        try {
            EzReflect.cacheEnabled = true
            EzReflect.memberResolver =
                object : MemberResolver by DefaultMemberResolver {
                    override fun methodsOf(clz: Class<*>): Array<java.lang.reflect.Method> {
                        if (clz != owner) return DefaultMemberResolver.methodsOf(clz)
                        entered.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                        return arrayOf(oldMethod)
                    }
                }
            val oldQuery =
                executor.submit<java.lang.reflect.Method> {
                    findMethod(owner) { nameStartsWith("value") }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            EzReflect.memberResolver =
                object : MemberResolver by DefaultMemberResolver {
                    override fun methodsOf(clz: Class<*>) = if (clz == owner) arrayOf(newMethod) else DefaultMemberResolver.methodsOf(clz)
                }
            resume.countDown()
            assertEquals(oldMethod, oldQuery.get(5, TimeUnit.SECONDS))
            assertEquals(newMethod, findMethod(owner) { nameStartsWith("value") })
            assertEquals(newMethod, findMethod(owner) { nameStartsWith("value") })
        } finally {
            resume.countDown()
            executor.shutdownNow()
            EzReflect.memberResolver = previous
            EzReflect.cacheEnabled = previousCache
        }
    }
}

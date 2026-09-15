package io.github.lingqiqi5211.ezhooktool.xposed

import android.content.res.Resources
import android.content.res.TypedArray
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResourceGetterTest {
    @Test
    fun captureInvalidatesAlreadyDequeuedInjection() {
        val lock = Any()
        val queue = ResourceInjectionQueue(lock)
        val calls = AtomicInteger()
        val oldTask = queue.pending { calls.incrementAndGet() }
        val dequeued = CountDownLatch(1)
        val worker = Thread {
            dequeued.countDown()
            oldTask.run()
        }
        synchronized(lock) {
            worker.start()
            assertTrue(dequeued.await(5, TimeUnit.SECONDS))
            queue.invalidate()
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(0, calls.get())
        queue.pending { calls.incrementAndGet() }.run()
        assertEquals(1, calls.get())
    }

    @Test
    fun rulePublicationKeepsInFlightSnapshotAndExactPackagePriority() {
        val old = ResourceRules<String>().withRule("*", "string", "title", "fallback")
            .withRule("host", "string", "title", "old")
        val current = old.withRule("host", "string", "title", "new")
            .withRule("*", "color", "title", "color")
        assertEquals("old", old.find("host", "string", "title"))
        assertEquals("new", current.find("host", "string", "title"))
        assertEquals("fallback", current.find("other", "string", "title"))
        assertEquals("color", current.find("host", "color", "title"))
        assertNull(old.find("host", "color", "title"))
        assertNull(ResourceRules<String>().find("host", "string", "title"))
        assertEquals("old", old.find("host", "string", "title"))
    }

    @Test
    fun integerAndColorNeverRoundTripThroughFloat() {
        assertEquals(16777217, EzResources.convert("getInteger", 16777217))
        assertEquals(0xff123457.toInt(), EzResources.convert("getColor", 0xff123457.toInt()))
        assertEquals(1.25f, EzResources.convert("getFraction", 1.25))
    }

    @Test
    fun pixelOffsetAndSizeHaveDifferentContracts() {
        assertEquals(0, EzResources.convert("getDimensionPixelOffset", 0.2f))
        assertEquals(1, EzResources.convert("getDimensionPixelSize", 0.2f))
        assertEquals(-1, EzResources.convert("getDimensionPixelSize", -0.2f))
        assertEquals(-1, EzResources.convert("getDimensionPixelOffset", -1.6f))
        assertEquals(2, EzResources.convert("getDimensionPixelSize", 1.6f))
        assertEquals(-2, EzResources.convert("getDimensionPixelSize", -2))
        assertEquals(0, EzResources.convert("getDimensionPixelSize", 0f))
    }

    @Test
    fun getterResultsHaveCorrectTypesAndIndependentArrays() {
        for (name in listOf("getDrawable", "getColorStateList", "getBoolean", "getIntArray", "getLayout")) {
            assertNull(EzResources.convert(name, "wrong"))
        }
        val original = intArrayOf(1, 2)
        val first = EzResources.convert("getIntArray", original) as IntArray
        first[0] = 99
        assertArrayEquals(intArrayOf(1, 2), EzResources.convert("getIntArray", original) as IntArray)
        assertNotSame(original, first)
        val texts = arrayOf("text")
        assertNotSame(texts, EzResources.convert("getStringArray", texts))
        assertNotSame(texts, EzResources.convert("getTextArray", texts))
        assertNull(EzResources.convert("getStringArray", arrayOf(1)))
    }

    @Test
    fun bothOwnersUseFullGetterSignatures() {
        val int = Int::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        for (method in listOf(
            Resources::class.java.getDeclaredMethod("getText", int, CharSequence::class.java),
            Resources::class.java.getDeclaredMethod("getColor", int, Resources.Theme::class.java),
            Resources::class.java.getDeclaredMethod("getDrawableForDensity", int, int),
            Resources::class.java.getDeclaredMethod("getString", int, Array<Any>::class.java),
            Resources::class.java.getDeclaredMethod("getQuantityText", int, int),
            Resources::class.java.getDeclaredMethod("getQuantityString", int, int, Array<Any>::class.java),
            TypedArray::class.java.getDeclaredMethod("getColor", int, int),
            TypedArray::class.java.getDeclaredMethod("getDimension", int, float),
            TypedArray::class.java.getDeclaredMethod("getText", int),
        )) {
            assertNotEquals(0, EzResources.getterMask(method), method.toString())
        }
        assertEquals(0, EzResources.getterMask(TypedArray::class.java.getDeclaredMethod("getResourceId", int, int)))
    }
}

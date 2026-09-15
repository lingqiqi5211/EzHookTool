package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.lingqiqi5211.ezhooktool.xposed.internal.AdditionalFields
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdditionalFieldsTest {
    data class Target(
        var value: Int,
    )

    @Test
    fun equalInstancesDoNotShareFields() {
        val first = Target(1)
        val second = Target(1)
        AdditionalFields.setInstance(first, "tag", "first")
        AdditionalFields.setInstance(second, "tag", "second")
        assertEquals("first", AdditionalFields.getInstance(first, "tag"))
        assertEquals("second", AdditionalFields.getInstance(second, "tag"))
        assertEquals("first", AdditionalFields.removeInstance(first, "tag"))
        assertEquals("second", AdditionalFields.getInstance(second, "tag"))
        AdditionalFields.removeInstance(second, "tag")
    }

    @Test
    fun mutableHashDoesNotLoseFieldsAndValuesRemainStrong() {
        val target = Target(1)
        val value = Any()
        AdditionalFields.setInstance(target, "tag", value)
        target.value = 2
        assertSame(value, AdditionalFields.getInstance(target, "tag"))
        assertSame(value, AdditionalFields.removeInstance(target, "tag"))
        assertNull(AdditionalFields.getInstance(target, "tag"))
    }
}

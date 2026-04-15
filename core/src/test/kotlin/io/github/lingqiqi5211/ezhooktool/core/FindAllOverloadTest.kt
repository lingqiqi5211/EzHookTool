package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private open class FindAllParent {
    @Suppress("unused")
    private val parentField = 1

    @Suppress("unused")
    fun parentOnly() = "parent"
}

private class FindAllChild : FindAllParent() {
    @Suppress("unused")
    private val childField = 2

    @Suppress("unused")
    fun childOnly() = "child"
}

private class FindAllConstructTarget {
    constructor()

    @Suppress("unused")
    constructor(value: Int)
}

class FindAllOverloadTest {
    @Test
    fun `findAllMethods without condition matches condition variant`() {
        val expected = findAllMethodsBy(FindAllChild::class.java) { true }.map { it.name }.toSet()
        val actual = findAllMethods(FindAllChild::class.java).map { it.name }.toSet()

        assertEquals(expected, actual)
        assertTrue(actual.contains("childOnly"))
        assertFalse(actual.contains("parentOnly"))
        assertEquals(actual, FindAllChild::class.java.findAllMethods().map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.findAllMethodsBy { true }.map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.resolve().methods().map { it.name }.toSet())
    }

    @Test
    fun `findAllFields without condition matches condition variant`() {
        val expected = findAllFieldsBy(FindAllChild::class.java) { true }.map { it.name }.toSet()
        val actual = findAllFields(FindAllChild::class.java).map { it.name }.toSet()

        assertEquals(expected, actual)
        assertTrue(actual.contains("childField"))
        assertFalse(actual.contains("parentField"))
        assertEquals(actual, FindAllChild::class.java.findAllFields().map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.findAllFieldsBy { true }.map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.resolve().fields().map { it.name }.toSet())
    }

    @Test
    fun `findAllConstructors without condition matches condition variant across entry points`() {
        val expected = findAllConstructorsBy(FindAllConstructTarget::class.java) { true }
            .map { it.parameterCount }
            .sorted()
        val actual = findAllConstructors(FindAllConstructTarget::class.java)
            .map { it.parameterCount }
            .sorted()
        val className = FindAllConstructTarget::class.java.name
        val classLoader = FindAllConstructTarget::class.java.classLoader

        assertEquals(listOf(0, 1), actual)
        assertEquals(expected, actual)
        assertEquals(actual, FindAllConstructTarget::class.java.findAllConstructors().map { it.parameterCount }.sorted())
        assertEquals(actual, FindAllConstructTarget::class.java.findAllConstructorsBy { true }.map { it.parameterCount }.sorted())
        assertEquals(actual, className.findAllConstructors(classLoader).map { it.parameterCount }.sorted())
        assertEquals(actual, className.findAllConstructorsBy(classLoader) { true }.map { it.parameterCount }.sorted())
        assertEquals(actual, reflect(classLoader) { className.findAllConstructors().map { it.parameterCount }.sorted() })
        assertEquals(actual, FindAllConstructTarget::class.java.resolve().constructors().map { it.parameterCount }.sorted())
    }

    @Test
    fun `string and reflect scope findAll overloads work without conditions`() {
        val className = FindAllChild::class.java.name
        val classLoader = FindAllChild::class.java.classLoader
        val methodNames = findAllMethods(FindAllChild::class.java).map { it.name }.toSet()
        val fieldNames = findAllFields(FindAllChild::class.java).map { it.name }.toSet()

        assertEquals(methodNames, findAllMethods(className, classLoader).map { it.name }.toSet())
        assertEquals(methodNames, className.findAllMethods(classLoader).map { it.name }.toSet())
        assertEquals(methodNames, findAllMethodsBy(className, classLoader) { true }.map { it.name }.toSet())
        assertEquals(methodNames, className.findAllMethodsBy(classLoader) { true }.map { it.name }.toSet())
        assertEquals(methodNames, reflect(classLoader) { className.findAllMethods().map { it.name }.toSet() })
        assertEquals(methodNames, reflect(classLoader) { className.findAllMethodsBy { true }.map { it.name }.toSet() })

        assertEquals(fieldNames, findAllFields(className, classLoader).map { it.name }.toSet())
        assertEquals(fieldNames, className.findAllFields(classLoader).map { it.name }.toSet())
        assertEquals(fieldNames, findAllFieldsBy(className, classLoader) { true }.map { it.name }.toSet())
        assertEquals(fieldNames, className.findAllFieldsBy(classLoader) { true }.map { it.name }.toSet())
        assertEquals(fieldNames, reflect(classLoader) { className.findAllFields().map { it.name }.toSet() })
        assertEquals(fieldNames, reflect(classLoader) { className.findAllFieldsBy { true }.map { it.name }.toSet() })
    }
}

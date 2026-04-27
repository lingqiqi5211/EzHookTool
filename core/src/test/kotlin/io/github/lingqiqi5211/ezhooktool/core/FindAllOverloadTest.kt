package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `findAllMethods without condition matches query variant`() {
        val expected = findAllMethods(FindAllChild::class.java) {}.map { it.name }.toSet()
        val actual = findAllMethods(FindAllChild::class.java).map { it.name }.toSet()

        assertEquals(expected, actual)
        assertTrue(actual.contains("childOnly"))
        assertFalse(actual.contains("parentOnly"))
        assertEquals(actual, FindAllChild::class.java.findAllMethods().map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.findAllMethods {}.map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.resolve().methods().map { it.name }.toSet())
    }

    @Test
    fun `findMethod query block matches a single method`() {
        val method = FindAllChild::class.java.findMethod {
            name("childOnly")
            paramCount(0)
            returnType(String::class.java)
        }

        assertEquals("childOnly", method.name)
    }

    @Test
    fun `method query can switch superclass search mode`() {
        val inherited = FindAllChild::class.java.findMethod {
            name("parentOnly")
            includeSuper()
        }
        val javaStyle = io.github.lingqiqi5211.ezhooktool.core.java.Methods.find(FindAllChild::class.java)
            .filterByName("parentOnly")
            .includeSuper()
            .first()

        assertEquals("parentOnly", inherited.name)
        assertEquals("parentOnly", javaStyle.name)
        assertNull(FindAllChild::class.java.findMethodOrNull {
            name("parentOnly")
            currentClassOnly()
        })
    }

    @Test
    fun `findAllFields without condition matches query variant`() {
        val expected = findAllFields(FindAllChild::class.java) {}.map { it.name }.toSet()
        val actual = findAllFields(FindAllChild::class.java).map { it.name }.toSet()

        assertEquals(expected, actual)
        assertTrue(actual.contains("childField"))
        assertFalse(actual.contains("parentField"))
        assertEquals(actual, FindAllChild::class.java.findAllFields().map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.findAllFields {}.map { it.name }.toSet())
        assertEquals(actual, FindAllChild::class.java.resolve().fields().map { it.name }.toSet())
    }

    @Test
    fun `findField query block matches a single field`() {
        val field = FindAllChild::class.java.findField {
            name("childField")
            type(Int::class.java)
            notStatic()
        }

        assertEquals("childField", field.name)
    }

    @Test
    fun `field query can switch superclass search mode`() {
        val inherited = FindAllChild::class.java.findField {
            name("parentField")
            includeSuper()
        }
        val javaStyle = io.github.lingqiqi5211.ezhooktool.core.java.Fields.find(FindAllChild::class.java)
            .filterByName("parentField")
            .includeSuper()
            .first()

        assertEquals("parentField", inherited.name)
        assertEquals("parentField", javaStyle.name)
        assertNull(FindAllChild::class.java.findFieldOrNull {
            name("parentField")
            currentClassOnly()
        })
    }

    @Test
    fun `findAllConstructors without condition matches condition variant across entry points`() {
        val expected = findAllConstructors(FindAllConstructTarget::class.java) {}
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
        assertEquals(actual, FindAllConstructTarget::class.java.findAllConstructors {}.map { it.parameterCount }.sorted())
        assertEquals(actual, className.findAllConstructors(classLoader).map { it.parameterCount }.sorted())
        assertEquals(actual, className.findAllConstructors(classLoader) {}.map { it.parameterCount }.sorted())
        assertEquals(actual, reflect(classLoader) { className.findAllConstructors().map { it.parameterCount }.sorted() })
        assertEquals(actual, reflect(classLoader) { className.findAllConstructors {}.map { it.parameterCount }.sorted() })
        assertEquals(actual, FindAllConstructTarget::class.java.resolve().constructors().map { it.parameterCount }.sorted())
    }

    @Test
    fun `findConstructor query block matches a single constructor`() {
        val constructor = FindAllConstructTarget::class.java.findConstructor {
            paramCount(1)
            params(Int::class.java)
        }

        assertEquals(1, constructor.parameterCount)
    }

    @Test
    fun `string and reflect scope findAll overloads work without conditions`() {
        val className = FindAllChild::class.java.name
        val classLoader = FindAllChild::class.java.classLoader
        val methodNames = findAllMethods(FindAllChild::class.java).map { it.name }.toSet()
        val fieldNames = findAllFields(FindAllChild::class.java).map { it.name }.toSet()

        assertEquals(methodNames, findAllMethods(className, classLoader).map { it.name }.toSet())
        assertEquals(methodNames, className.findAllMethods(classLoader).map { it.name }.toSet())
        assertEquals(methodNames, findAllMethods(className, classLoader) {}.map { it.name }.toSet())
        assertEquals(methodNames, className.findAllMethods(classLoader) {}.map { it.name }.toSet())
        assertEquals(methodNames, reflect(classLoader) { className.findAllMethods().map { it.name }.toSet() })
        assertEquals(methodNames, reflect(classLoader) { className.findAllMethods {}.map { it.name }.toSet() })

        assertEquals(fieldNames, findAllFields(className, classLoader).map { it.name }.toSet())
        assertEquals(fieldNames, className.findAllFields(classLoader).map { it.name }.toSet())
        assertEquals(fieldNames, findAllFields(className, classLoader) {}.map { it.name }.toSet())
        assertEquals(fieldNames, className.findAllFields(classLoader) {}.map { it.name }.toSet())
        assertEquals(fieldNames, reflect(classLoader) { className.findAllFields().map { it.name }.toSet() })
        assertEquals(fieldNames, reflect(classLoader) { className.findAllFields {}.map { it.name }.toSet() })
    }
}

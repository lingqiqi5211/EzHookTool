package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

class ExactLookupTest {
    open class Parent {
        @JvmField
        val inherited: String = "parent"

        fun inheritedWork(value: String): String = value
    }

    class Child : Parent() {
        @JvmField
        val own: Int = 1

        fun ownWork(
            value: String,
            count: Int,
        ): String = value.repeat(count)
    }

    class Overloaded {
        constructor(value: String)

        constructor(value: Int)
    }

    private val previousResolver = EzReflect.memberResolver
    private val previousCacheEnabled = EzReflect.cacheEnabled
    private val previousDebugMode = EzReflect.debugMode

    // 用于确认额外条件与自定义解析器仍走通用扫描。
    private val rejectingResolver =
        object : MemberResolver {
            override fun methodsOf(clz: Class<*>): Array<Method> = error("methodsOf must not run for $clz")

            override fun fieldsOf(clz: Class<*>): Array<Field> = error("fieldsOf must not run for $clz")

            override fun constructorsOf(clz: Class<*>): Array<Constructor<*>> = error("constructorsOf must not run for $clz")
        }

    @BeforeEach
    fun prepare() {
        EzReflect.cacheEnabled = true
        EzReflect.debugMode = false
        EzReflect.memberResolver = DefaultMemberResolver
        EzReflect.clearCache()
    }

    @AfterEach
    fun restore() {
        EzReflect.memberResolver = previousResolver
        EzReflect.cacheEnabled = previousCacheEnabled
        EzReflect.debugMode = previousDebugMode
        EzReflect.clearCache()
    }

    @Test
    fun nameAndParameterTypesLookUpAcrossSuperclasses() {
        val own =
            findMethod(Child::class.java) {
                name("ownWork")
                params(String::class.java, Int::class.java)
            }
        assertEquals(Child::class.java, own.declaringClass)
        assertTrue(own.isAccessible)

        val inherited =
            findMethod(Child::class.java) {
                name("inheritedWork")
                params(String::class.java)
            }
        assertEquals(Parent::class.java, inherited.declaringClass)

        assertNull(
            findMethodOrNull(Child::class.java) {
                name("inheritedWork")
                params(String::class.java)
                findOnlyClass()
            },
        )
        assertNull(
            findMethodOrNull(Child::class.java) {
                name("ownWork")
                params(Int::class.java)
            },
        )
        val missing =
            assertThrows<MemberNotFoundException> {
                findMethod(Child::class.java) {
                    name("ownWork")
                    params(String::class.java)
                    findOnlyClass()
                }
            }
        assertTrue(missing.message!!.contains("ownWork"))
    }

    @Test
    fun fieldNameLookUpAcrossSuperclasses() {
        assertEquals(Child::class.java, findField(Child::class.java) { name("own") }.declaringClass)
        assertEquals(Parent::class.java, findField(Child::class.java) { name("inherited") }.declaringClass)
        assertNull(
            findFieldOrNull(Child::class.java) {
                name("inherited")
                findOnlyClass()
            },
        )
        assertThrows<MemberNotFoundException> { findField(Child::class.java) { name("missing") } }
    }

    @Test
    fun exactParameterTypesResolveConstructors() {
        val constructor = findConstructor(Overloaded::class.java) { params(Int::class.java) }
        assertArrayEquals(arrayOf<Class<*>>(Int::class.java), constructor.parameterTypes)
        assertThrows<MemberNotFoundException> {
            findConstructor(Overloaded::class.java) { params(Double::class.java) }
        }
    }

    @Test
    fun otherConditionsKeepTheGenericScan() {
        EzReflect.memberResolver = rejectingResolver
        assertThrows<IllegalStateException> { findMethodOrNull(Child::class.java) { name("ownWork") } }
        assertThrows<IllegalStateException> {
            findMethodOrNull(Child::class.java) {
                name("ownWork")
                params(String::class.java, Int::class.java)
                returnType(String::class.java)
            }
        }
        assertThrows<IllegalStateException> {
            findMethodOrNull(Child::class.java) {
                name("ownWork")
                name("ownWork")
                params(String::class.java, Int::class.java)
            }
        }
        assertThrows<IllegalStateException> { findFieldOrNull(Child::class.java) { nameStartsWith("ow") } }
        assertThrows<IllegalStateException> { findConstructorOrNull(Overloaded::class.java) { paramCount(1) } }
    }

    @Test
    fun customResolverStillControlsExactQueries() {
        EzReflect.memberResolver =
            object : MemberResolver {
                override fun methodsOf(clz: Class<*>): Array<Method> = emptyArray()

                override fun fieldsOf(clz: Class<*>): Array<Field> = emptyArray()

                override fun constructorsOf(clz: Class<*>): Array<Constructor<*>> = emptyArray()
            }
        assertNull(
            findMethodOrNull(Child::class.java) {
                name("ownWork")
                params(String::class.java, Int::class.java)
            },
        )
        assertNull(findFieldOrNull(Child::class.java) { name("own") })
        assertNull(findConstructorOrNull(Overloaded::class.java) { params(Int::class.java) })
    }

    @Test
    fun exactResultsAreCachedAndStillResolvedWithCacheDisabled() {
        val first =
            findMethod(Child::class.java) {
                name("ownWork")
                params(String::class.java, Int::class.java)
            }
        val second =
            findMethod(Child::class.java) {
                name("ownWork")
                params(String::class.java, Int::class.java)
            }
        assertSame(first, second)

        EzReflect.cacheEnabled = false
        assertEquals(
            Child::class.java,
            findMethod(Child::class.java) {
                name("ownWork")
                params(String::class.java, Int::class.java)
            }.declaringClass,
        )
    }
}

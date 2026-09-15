package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CacheSemanticsTest {
    open class Parent {
        private var token: Int = 0

        open fun work(): String = "parent"

        fun inherited() = Unit
    }

    class Child : Parent() {
        @JvmField val token: Int = 1

        final override fun work(): String = "child"

        fun other() = Unit
    }

    class Constructors {
        constructor()
        constructor(value: String)
    }

    private val loader = Child::class.java.classLoader
    private val previousResolver = EzReflect.classResolver
    private val previousEnabled = EzReflect.cacheEnabled

    @BeforeEach
    fun prepare() {
        EzReflect.cacheEnabled = true
        EzReflect.classResolver =
            object : ClassResolver {
                override fun classNamesOf(classLoader: ClassLoader): Sequence<String> =
                    sequenceOf(Child::class.java.name, Parent::class.java.name)
            }
        EzReflect.clearCache()
    }

    @AfterEach
    fun restore() {
        EzReflect.classResolver = previousResolver
        EzReflect.cacheEnabled = previousEnabled
        EzReflect.clearCache()
    }

    @Test
    fun repeatedConditionsDoNotReadLooserCachedResults() {
        for (enabled in listOf(false, true)) {
            EzReflect.cacheEnabled = enabled
            findMethod(Child::class.java) { name("other") }
            assertNull(
                findMethodOrNull(Child::class.java) {
                    name("work")
                    name("other")
                },
            )
            findField(Child::class.java) { name("token") }
            assertNull(
                findFieldOrNull(Child::class.java) {
                    name("missing")
                    name("token")
                },
            )
            findConstructor(Constructors::class.java) { paramCount(1) }
            assertNull(
                findConstructorOrNull(Constructors::class.java) {
                    paramCount(0)
                    paramCount(1)
                },
            )
            findClassIf(loader) { name(Child::class.java.name) }
            assertNull(
                findClassIfOrNull(loader) {
                    name(Parent::class.java.name)
                    name(Child::class.java.name)
                },
            )
            findMethod(Child::class.java) {
                name("other")
                isPublic()
            }
            assertNull(
                findMethodOrNull(Child::class.java) {
                    name("other")
                    notPublic()
                    isPublic()
                },
            )
        }
    }

    @Test
    fun hotFirstDoesNotBypassSingleRequirement() {
        findClassIf(loader) { nameStartsWith(CacheSemanticsTest::class.java.name) }
        assertThrows<SingleResultExpectedException> {
            findClassIf(loader) {
                nameStartsWith(CacheSemanticsTest::class.java.name)
                findSingle()
            }
        }
    }

    @Test
    fun repeatedNestedConditionsRemainConjunctive() {
        findClassIf(loader) {
            name(Child::class.java.name)
            hasMethod { name("other") }
        }
        assertNull(
            findClassIfOrNull(loader) {
                name(Child::class.java.name)
                hasMethod { name("absent") }
                hasMethod { name("other") }
            },
        )
    }

    @Test
    fun nestedSearchScopeAffectsExecutionAndCache() {
        assertNotNull(
            findClassIfOrNull(loader) {
                name(Child::class.java.name)
                hasMethod {
                    name("inherited")
                    findAndSuper()
                }
            },
        )
        assertNull(
            findClassIfOrNull(loader) {
                name(Child::class.java.name)
                hasMethod {
                    name("inherited")
                    findOnlyClass()
                }
            },
        )
        assertNotNull(
            findClassIfOrNull(loader) {
                name(Child::class.java.name)
                hasField {
                    name("token")
                    findOnlyClass()
                    findSingle()
                }
            },
        )
        assertNull(
            findClassIfOrNull(loader) {
                name(Child::class.java.name)
                hasField {
                    name("token")
                    findAndSuper()
                    findSingle()
                }
            },
        )
    }

    @Test
    fun filteredParentDoesNotPrewarmChildExactLookups() {
        assertEquals(
            Parent::class.java,
            findAllMethods(Child::class.java) {
                name("work")
                notFinal()
            }.single().declaringClass,
        )
        assertEquals(
            Child::class.java,
            findMethod(Child::class.java) {
                name("work")
                params()
                returnType(String::class.java)
            }.declaringClass,
        )
        assertEquals(
            Parent::class.java,
            findAllFields(Child::class.java) {
                name("token")
                notFinal()
            }.single().declaringClass,
        )
        assertEquals(
            Child::class.java,
            findField(Child::class.java) {
                name("token")
                type(Int::class.java)
                notStatic()
            }.declaringClass,
        )
    }

    @Test
    fun callersCannotChangeStoredListsOnMissOrHit() {
        repeat(3) {
            val methods = findAllMethods(Child::class.java) { name("work") }
            assertEquals(1, methods.size)
            (methods as MutableList).clear()
            val fields = findAllFields(Child::class.java) { name("token") }
            assertEquals(1, fields.size)
            (fields as MutableList).clear()
            val constructors = findAllConstructors(Constructors::class.java) { isPublic() }
            assertEquals(2, constructors.size)
            (constructors as MutableList).clear()
            val classes = findAllClassesIf(loader) { }
            assertEquals(2, classes.size)
            (classes as MutableList).clear()
        }
    }

    @Test
    fun cacheHitsRestoreAccessibleMembers() {
        val method = findMethod(Child::class.java) { name("other") }
        method.isAccessible = false
        assertSame(method, findMethod(Child::class.java) { name("other") })
        assertTrue(method.isAccessible)

        val methods = findAllMethods(Child::class.java) { name("other") }.single()
        methods.isAccessible = false
        assertTrue(findAllMethods(Child::class.java) { name("other") }.single().isAccessible)

        val field = findField(Child::class.java) { name("token") }
        field.isAccessible = false
        assertSame(field, findField(Child::class.java) { name("token") })
        assertTrue(field.isAccessible)

        val fields = findAllFields(Child::class.java) { name("token") }.single()
        fields.isAccessible = false
        assertTrue(findAllFields(Child::class.java) { name("token") }.single().isAccessible)

        val constructor = findConstructor(Constructors::class.java) { paramCount(1) }
        constructor.isAccessible = false
        assertSame(constructor, findConstructor(Constructors::class.java) { paramCount(1) })
        assertTrue(constructor.isAccessible)

        val constructors = findAllConstructors(Constructors::class.java) { paramCount(1) }.single()
        constructors.isAccessible = false
        assertTrue(findAllConstructors(Constructors::class.java) { paramCount(1) }.single().isAccessible)
    }
}

package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.query.ClassQuery
import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.GenericTypeMatcher
import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import io.github.lingqiqi5211.ezhooktool.core.query.QueryResultMode
import io.github.lingqiqi5211.ezhooktool.core.query.QueryScope
import io.github.lingqiqi5211.ezhooktool.core.query.classQuery
import io.github.lingqiqi5211.ezhooktool.core.query.constructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.fieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.methodQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.function.Predicate

class QueryPlanTest {
    open class Parent {
        private val token: String = "parent"

        open fun common(): String = "parent"

        fun inherited() = Unit
    }

    class Target : Parent {
        @JvmField val token: String

        constructor() {
            token = ""
        }

        @Throws(IllegalArgumentException::class)
        constructor(value: String) {
            token = value
        }

        constructor(value: Int) {
            token = value.toString()
        }

        override fun common(): String = "child"

        @Throws(IllegalArgumentException::class)
        fun accept(value: String): String = value

        fun accept(value: Int): Int = value

        fun <T> generic(value: T): T = value
    }

    private val target = Target::class.java
    private val loader = target.classLoader
    private val originalResolver = EzReflect.classResolver
    private val originalCacheEnabled = EzReflect.cacheEnabled
    private val stringMethod = target.getDeclaredMethod("accept", String::class.java)
    private val intMethod = target.getDeclaredMethod("accept", Int::class.java)
    private val stringConstructor = target.getDeclaredConstructor(String::class.java)
    private val intConstructor = target.getDeclaredConstructor(Int::class.java)

    @BeforeEach
    fun prepare() {
        EzReflect.cacheEnabled = true
        EzReflect.classResolver =
            object : ClassResolver {
                override fun classNamesOf(classLoader: ClassLoader): Sequence<String> = sequenceOf(target.name, Parent::class.java.name)
            }
        EzReflect.clearCache()
    }

    @AfterEach
    fun restore() {
        EzReflect.classResolver = originalResolver
        EzReflect.cacheEnabled = originalCacheEnabled
        EzReflect.clearCache()
    }

    @Test
    fun repeatedTermsAndFlagsHaveCompleteAutomaticKeys() {
        val method =
            methodQuery {
                name("accept")
                nameContains("acc")
                nameContains("cept")
                isPublic()
                isPublic()
            }.freeze(QueryResultMode.FIRST)
        val sameMethod =
            methodQuery {
                name("accept")
                nameContains("acc")
                nameContains("cept")
                isPublic()
                isPublic()
            }.freeze(QueryResultMode.FIRST)
        assertNotNull(method.cacheKey)
        assertEquals(method.cacheKey, sameMethod.cacheKey)
        assertTrue(method.matches(stringMethod))
        assertNotEquals(
            method.cacheKey,
            methodQuery {
                name("accept")
                isPublic()
            }.freeze(QueryResultMode.FIRST).cacheKey,
        )

        val contradictoryMethod =
            methodQuery {
                notPublic()
                isPublic()
            }.freeze(QueryResultMode.FIRST)
        val contradictoryField =
            fieldQuery {
                name("missing")
                name("token")
            }.freeze(QueryResultMode.FIRST)
        val contradictoryConstructor =
            constructorQuery {
                paramCount(0)
                paramCount(1)
            }.freeze(QueryResultMode.FIRST)
        val contradictoryClass =
            classQuery {
                name(Parent::class.java.name)
                name(target.name)
            }.freeze(QueryResultMode.FIRST)
        for (plan in listOf(contradictoryMethod, contradictoryField, contradictoryConstructor, contradictoryClass)) {
            assertNotNull(plan.cacheKey)
        }
        assertFalse(contradictoryMethod.matches(stringMethod))
        assertFalse(contradictoryField.matches(target.getDeclaredField("token")))
        assertFalse(contradictoryConstructor.matches(stringConstructor))
        assertFalse(contradictoryClass.matchesName(target.name))
    }

    @Test
    fun repeatedConditionsRemainAndWhenTheLooserQueriesAreCached() {
        for (enabled in listOf(false, true)) {
            EzReflect.clearCache()
            EzReflect.cacheEnabled = enabled
            findMethod(target) { name("accept") }
            findField(target) { name("token") }
            findConstructor(target) { paramCount(1) }
            findClassIf(loader) { name(target.name) }
            repeat(2) {
                assertNull(
                    findMethodOrNull(target) {
                        name("missing")
                        name("accept")
                    },
                )
                assertNull(
                    findFieldOrNull(target) {
                        name("missing")
                        name("token")
                    },
                )
                assertNull(
                    findConstructorOrNull(target) {
                        paramCount(0)
                        paramCount(1)
                    },
                )
                assertNull(
                    findClassIfOrNull(loader) {
                        name(Parent::class.java.name)
                        name(target.name)
                    },
                )
            }
        }
    }

    @Test
    fun builderMutationCannotChangeFrozenTermsScopeModeOrManualKey() {
        val builder =
            methodQuery {
                name("accept")
                findOnlyClass()
            }
        val plan = builder.freeze(QueryResultMode.FIRST)
        val key = plan.cacheKey
        builder.name("missing")
        builder.findAndSuper()
        builder.findSingle()
        builder.cacheKey("later")
        assertEquals(key, plan.cacheKey)
        assertEquals("name=accept", plan.description)
        assertEquals(QueryScope.DECLARED, plan.scope)
        assertEquals(QueryResultMode.FIRST, plan.resultMode)
        assertTrue(plan.matches(stringMethod))
        assertFalse(builder.freeze(QueryResultMode.FIRST).matches(stringMethod))

        val manual = builder.freeze(QueryResultMode.FIRST)
        builder.cacheKey("changed-again")
        assertNotEquals(manual.cacheKey, builder.freeze(QueryResultMode.FIRST).cacheKey)

        lateinit var mutable: MethodQuery
        mutable =
            methodQuery {
                filter {
                    mutable.name("missing")
                    true
                }
            }
        val snapshot = mutable.freeze(QueryResultMode.ALL)
        assertTrue(snapshot.matches(stringMethod))
        assertTrue(snapshot.matches(intMethod))
    }

    @Test
    fun javaArrayArgumentsAreCopiedWhenRegisteredRatherThanWhenFrozen() {
        for (name in listOf("parameterTypes", "parameterTypesAssignableFrom", "exceptionTypes")) {
            val original = if (name == "exceptionTypes") IllegalArgumentException::class.java else String::class.java
            val changed = if (name == "exceptionTypes") IllegalStateException::class.java else Int::class.java
            val types = arrayOf<Class<*>>(original)
            val method = MethodQuery()
            val constructor = ConstructorQuery()
            registerArray(method, name, types)
            registerArray(constructor, name, types)
            types[0] = changed
            val methodPlan = method.freeze(QueryResultMode.FIRST)
            val constructorPlan = constructor.freeze(QueryResultMode.FIRST)
            assertTrue(methodPlan.matches(stringMethod), name)
            assertFalse(methodPlan.matches(intMethod), name)
            assertTrue(constructorPlan.matches(stringConstructor), name)
            assertFalse(constructorPlan.matches(intConstructor), name)

            val expectedMethod = MethodQuery()
            val expectedConstructor = ConstructorQuery()
            registerArray(expectedMethod, name, arrayOf<Class<*>>(original))
            registerArray(expectedConstructor, name, arrayOf<Class<*>>(original))
            assertEquals(expectedMethod.freeze(QueryResultMode.FIRST).cacheKey, methodPlan.cacheKey)
            assertEquals(expectedConstructor.freeze(QueryResultMode.FIRST).cacheKey, constructorPlan.cacheKey)
            types[0] = Any::class.java
            assertTrue(methodPlan.matches(stringMethod), name)
            assertTrue(constructorPlan.matches(stringConstructor), name)
        }
    }

    @Test
    fun javaVagueAndGenericArraysAreAlsoRegistrationSnapshots() {
        val types = arrayOf<Any>(String::class.java)
        val method = MethodQuery()
        val constructor = ConstructorQuery()
        registerArray(method, "parameterTypesVague", types)
        registerArray(constructor, "parameterTypesVague", types)
        types[0] = Int::class.java
        assertTrue(method.freeze(QueryResultMode.FIRST).matches(stringMethod))
        assertFalse(method.freeze(QueryResultMode.FIRST).matches(intMethod))
        assertTrue(constructor.freeze(QueryResultMode.FIRST).matches(stringConstructor))
        assertFalse(constructor.freeze(QueryResultMode.FIRST).matches(intConstructor))

        val matchers = arrayOf(GenericTypeMatcher.exact(String::class.java))
        val genericMethod = MethodQuery()
        val genericConstructor = ConstructorQuery()
        registerArray(genericMethod, "genericParameterTypes", matchers)
        registerArray(genericConstructor, "genericParameterTypes", matchers)
        matchers[0] = GenericTypeMatcher.exact(Int::class.java)
        val methodPlan = genericMethod.freeze(QueryResultMode.FIRST)
        val constructorPlan = genericConstructor.freeze(QueryResultMode.FIRST)
        assertTrue(methodPlan.matches(stringMethod))
        assertFalse(methodPlan.matches(intMethod))
        assertTrue(constructorPlan.matches(stringConstructor))
        assertFalse(constructorPlan.matches(intConstructor))
        assertNull(methodPlan.cacheKey)
        assertNull(constructorPlan.cacheKey)
    }

    @Test
    fun nestedPlansDoNotRetainMutableMemberBuilders() {
        lateinit var method: MethodQuery
        lateinit var field: FieldQuery
        lateinit var constructor: ConstructorQuery
        val builder =
            classQuery {
                hasMethod {
                    method = this
                    name("common")
                    findOnlyClass()
                    findSingle()
                }
                hasField {
                    field = this
                    name("token")
                    findOnlyClass()
                    findSingle()
                }
                hasConstructor {
                    constructor = this
                    params(String::class.java)
                    findSingle()
                }
            }
        val plan = builder.freeze(QueryResultMode.FIRST)
        method.findAndSuper()
        method.name("missing")
        method.cacheKey("changed-method")
        field.findAndSuper()
        field.name("missing")
        constructor.params(Int::class.java)
        constructor.cacheKey("changed-constructor")
        val laterPlan = builder.freeze(QueryResultMode.FIRST)
        assertNotNull(plan.cacheKey)
        assertEquals(plan.cacheKey, laterPlan.cacheKey)
        assertTrue(plan.matches(target))
        assertTrue(laterPlan.matches(target))
    }

    @Test
    fun nestedScopeAndUniquenessAffectBothKeysAndExecution() {
        val declared =
            classQuery {
                hasMethod {
                    name("common")
                    findOnlyClass()
                    findSingle()
                }
                hasField {
                    name("token")
                    findOnlyClass()
                    findSingle()
                }
            }.freeze(QueryResultMode.FIRST)
        val hierarchy =
            classQuery {
                hasMethod {
                    name("common")
                    findAndSuper()
                    findSingle()
                }
                hasField {
                    name("token")
                    findAndSuper()
                    findSingle()
                }
            }.freeze(QueryResultMode.FIRST)
        val exists =
            classQuery {
                hasMethod {
                    name("common")
                    findAndSuper()
                }
                hasField {
                    name("token")
                    findAndSuper()
                }
            }.freeze(QueryResultMode.FIRST)
        assertTrue(declared.matches(target))
        assertFalse(hierarchy.matches(target))
        assertTrue(exists.matches(target))
        assertNotEquals(declared.cacheKey, hierarchy.cacheKey)
        assertNotEquals(hierarchy.cacheKey, exists.cacheKey)
        assertFalse(
            classQuery {
                hasField {
                    name("token")
                    findAndSuper()
                    findSingle()
                }
            }.freeze(QueryResultMode.FIRST).matches(target),
        )
        assertFalse(
            classQuery {
                hasConstructor {
                    paramCount(1)
                    findSingle()
                }
            }.freeze(QueryResultMode.FIRST).matches(target),
        )
        assertTrue(classQuery { hasConstructor { paramCount(1) } }.freeze(QueryResultMode.FIRST).matches(target))
        assertTrue(classQuery { hasMethod { name("inherited") } }.freeze(QueryResultMode.FIRST).matches(target))
        assertFalse(
            classQuery {
                hasMethod {
                    name("inherited")
                    findOnlyClass()
                }
            }.freeze(QueryResultMode.FIRST).matches(target),
        )

        val repeated =
            classQuery {
                hasMethod { name("common") }
                hasMethod { name("absent") }
            }.freeze(QueryResultMode.FIRST)
        assertNotNull(repeated.cacheKey)
        assertFalse(repeated.matches(target))
    }

    @Test
    fun opaqueConditionsOnlyCacheWithAnExplicitManualKey() {
        val plans =
            listOf(
                methodQuery { filter { true } }.freeze(QueryResultMode.FIRST),
                fieldQuery { filter(Predicate { true }) }.freeze(QueryResultMode.FIRST),
                constructorQuery { filter { true } }.freeze(QueryResultMode.FIRST),
                classQuery { filter { true } }.freeze(QueryResultMode.FIRST),
                methodQuery { genericReturnType(GenericTypeMatcher.typeVariableNamed("T")) }.freeze(QueryResultMode.FIRST),
                classQuery { hasMethod { filter { true } } }.freeze(QueryResultMode.FIRST),
                classQuery { hasField { filter { true } } }.freeze(QueryResultMode.FIRST),
                classQuery {
                    hasConstructor {
                        genericParameterTypes(
                            GenericTypeMatcher.exact(String::class.java),
                        )
                    }
                }.freeze(QueryResultMode.FIRST),
            )
        plans.forEach { assertNull(it.cacheKey) }
        val manual =
            methodQuery {
                filter { true }
                cacheKey("explicit")
            }
        assertNotNull(manual.freeze(QueryResultMode.FIRST).cacheKey)
        assertEquals(
            manual.freeze(QueryResultMode.FIRST).cacheKey,
            methodQuery {
                filter { false }
                cacheKey("explicit")
            }.freeze(QueryResultMode.FIRST).cacheKey,
        )
        assertNotEquals(manual.freeze(QueryResultMode.FIRST).cacheKey, manual.freeze(QueryResultMode.ALL).cacheKey)
        manual.findSingle()
        assertNotEquals(manual.freeze(QueryResultMode.FIRST).cacheKey, manual.freeze(QueryResultMode.ALL).cacheKey)
        val singleKey = manual.freeze(QueryResultMode.FIRST).cacheKey
        manual.findOnlyClass()
        assertNotEquals(singleKey, manual.freeze(QueryResultMode.FIRST).cacheKey)
        assertNotNull(
            classQuery {
                hasMethod {
                    filter { true }
                    cacheKey("nested")
                }
            }.freeze(QueryResultMode.FIRST).cacheKey,
        )
        assertNotNull(
            classQuery {
                cacheKey("outer")
                hasMethod { filter { true } }
            }.freeze(QueryResultMode.FIRST).cacheKey,
        )
    }

    @Test
    fun firstExactlyOneAndAllNeverShareCachedResults() {
        for (enabled in listOf(false, true)) {
            EzReflect.clearCache()
            EzReflect.cacheEnabled = enabled
            repeat(2) {
                findMethod(target) { name("accept") }
                assertThrows<SingleResultExpectedException> {
                    findMethod(target) {
                        name("accept")
                        findSingle()
                    }
                }
                assertEquals(
                    2,
                    findAllMethods(target) {
                        name("accept")
                        findSingle()
                    }.size,
                )

                findField(target) {
                    name("token")
                    findAndSuper()
                }
                assertThrows<SingleResultExpectedException> {
                    findField(target) {
                        name("token")
                        findAndSuper()
                        findSingle()
                    }
                }
                assertEquals(
                    2,
                    findAllFields(target) {
                        name("token")
                        findAndSuper()
                        findSingle()
                    }.size,
                )

                findConstructor(target) { paramCount(1) }
                assertThrows<SingleResultExpectedException> {
                    findConstructor(target) {
                        paramCount(1)
                        findSingle()
                    }
                }
                assertEquals(
                    2,
                    findAllConstructors(target) {
                        paramCount(1)
                        findSingle()
                    }.size,
                )

                findClassIf(loader) { }
                assertThrows<SingleResultExpectedException> { findClassIf(loader) { findSingle() } }
                assertEquals(2, findAllClassesIf(loader) { findSingle() }.size)
            }
        }
    }

    @Test
    fun classNameTermsRunBeforeClassLoadingEvenWhenRegisteredLast() {
        val loaded = mutableListOf<String>()
        val checkingLoader =
            object : ClassLoader(loader) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> {
                    loaded += name
                    return super.loadClass(name, resolve)
                }
            }
        val filtered = mutableListOf<Class<*>>()
        lateinit var builder: ClassQuery
        val result =
            findAllClassesIf(checkingLoader) {
                builder = this
                filter {
                    filtered += this
                    builder.name("added-during-execution")
                    true
                }
                name(target.name)
            }
        assertEquals(listOf(target), result)
        assertEquals(listOf(target.name), loaded)
        assertEquals(listOf(target), filtered)
    }

    @Test
    fun typeKeysRetainClassIdentityRatherThanSimpleOrBinaryNames() {
        assertNotEquals(
            fieldQuery { type(java.util.Date::class.java) }.freeze(QueryResultMode.FIRST).cacheKey,
            fieldQuery { type(java.sql.Date::class.java) }.freeze(QueryResultMode.FIRST).cacheKey,
        )
        val original = QueryPlanIdentityType::class.java
        val bytes = original.getResourceAsStream("/${original.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        val isolated =
            object : ClassLoader(original.classLoader) {
                fun define(): Class<*> = defineClass(original.name, bytes, 0, bytes.size)
            }.define()
        assertEquals(original.name, isolated.name)
        assertNotEquals(
            methodQuery { params(original) }.freeze(QueryResultMode.FIRST).cacheKey,
            methodQuery { params(isolated) }.freeze(QueryResultMode.FIRST).cacheKey,
        )
    }

    private fun registerArray(
        builder: Any,
        name: String,
        values: Any,
    ) {
        // 反射调用保留 Java vararg 数组身份，避免 Kotlin spread 自带复制掩盖问题。
        builder.javaClass.getMethod(name, values.javaClass).invoke(builder, values)
    }
}

private class QueryPlanIdentityType

package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Serializable

class BestMatchSelectionTest {
    class Target {
        fun pick(value: Any): Any = value

        fun pick(value: CharSequence): Any = value

        fun ambiguous(value: CharSequence): Any = value

        fun ambiguous(value: Serializable): Any = value

        fun cross(
            first: String,
            second: Any,
        ) = first

        fun cross(
            first: Any,
            second: String,
        ) = second

        fun number(value: Int) = value

        fun number(value: Int?) = value

        fun wide(value: Long) = value

        fun many(vararg values: String) = values.size

        fun route(value: String) = "instance"

        companion object {
            @JvmStatic fun route(value: Any) = "static"
        }
    }

    class Constructed {
        constructor(value: Any)
        constructor(value: CharSequence)
    }

    open class Parent {
        open fun value(): Any = "parent"
    }

    class Child : Parent() {
        override fun value(): String = "child"
    }

    private val previousResolver = EzReflect.memberResolver
    private val previousCache = EzReflect.cacheEnabled

    @AfterEach
    fun restore() {
        EzReflect.memberResolver = previousResolver
        EzReflect.cacheEnabled = previousCache
        EzReflect.clearCache()
    }

    @Test
    fun candidateOrderAndCacheDoNotChangeSpecificityOrAmbiguity() {
        for (reverse in listOf(false, true)) {
            EzReflect.memberResolver =
                object : MemberResolver by DefaultMemberResolver {
                    override fun methodsOf(clz: Class<*>) = clz.declaredMethods.let { if (reverse) it.reversedArray() else it }

                    override fun constructorsOf(clz: Class<*>) = clz.declaredConstructors.let { if (reverse) it.reversedArray() else it }
                }
            for (cache in listOf(false, true)) {
                EzReflect.cacheEnabled = cache
                repeat(2) {
                    assertEquals(CharSequence::class.java, findMethodBestMatch(Target::class.java, "pick", "text").parameterTypes.single())
                    assertEquals(
                        CharSequence::class.java,
                        findMethodBestMatch(Target::class.java, "pick", *arrayOf<Any?>(null)).parameterTypes.single(),
                    )
                    assertEquals(
                        CharSequence::class.java,
                        findConstructorBestMatch(Constructed::class.java, "text").parameterTypes.single(),
                    )
                    assertThrows<SingleResultExpectedException> { findMethodBestMatch(Target::class.java, "ambiguous", "text") }
                    assertThrows<SingleResultExpectedException> {
                        findMethodBestMatch(
                            Target::class.java,
                            "ambiguous",
                            *arrayOf<Any?>(null),
                        )
                    }
                    assertThrows<SingleResultExpectedException> { findMethodBestMatch(Target::class.java, "cross", "a", "b") }
                }
            }
        }
    }

    @Test
    fun exactPrimitiveAndBoxedMatchBeforeReferenceConversions() {
        assertEquals(
            Int::class.javaPrimitiveType,
            findMethodBestMatch(Target::class.java, "number", Int::class.javaPrimitiveType!!).parameterTypes.single(),
        )
        assertEquals(
            Int::class.javaObjectType,
            findMethodBestMatch(Target::class.java, "number", *arrayOf<Any?>(1)).parameterTypes.single(),
        )
        assertThrows<MemberNotFoundException> { findMethodBestMatch(Target::class.java, "wide", *arrayOf<Any?>(1)) }
    }

    @Test
    fun autoCallFiltersStaticAndInstanceBeforeSelection() {
        assertEquals("static", invokeAutoMatchedMethod(Target::class.java, null, "route", arrayOf("text")))
        assertEquals("instance", invokeAutoMatchedMethod(Target::class.java, Target(), "route", arrayOf("text")))
    }

    @Test
    fun covariantOverrideWinsOverParentAndBridge() {
        val method = findMethodBestMatch(Child::class.java, "value", *emptyArray<Class<*>>())
        assertEquals(Child::class.java, method.declaringClass)
        assertEquals(String::class.java, method.returnType)
        assertFalse(method.isBridge)
    }

    @Test
    fun varargRequiresExplicitArrayAndDoesNotPackArguments() {
        assertTrue(findMethodBestMatch(Target::class.java, "many", *arrayOf<Any?>(arrayOf("a", "b"))).isVarArgs)
        assertThrows<MemberNotFoundException> { findMethodBestMatch(Target::class.java, "many", "a", "b") }
    }
}

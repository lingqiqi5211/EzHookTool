package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private class ConditionalLoginActivity {
    @Suppress("unused")
    private val token = "value"

    @Suppress("unused")
    fun login(name: String, password: String): Boolean = name == password
}

private class ConditionalLoginDialog {
    @Suppress("unused")
    fun login(): Boolean = true
}

private class ConditionalMethodTarget {
    @Suppress("unused")
    fun sameName(): String = "a"

    @Suppress("unused")
    fun sameName(value: String): String = value
}

class ConditionalQueryTest {
    private val classLoader = ConditionalLoginActivity::class.java.classLoader
    private val originalClassResolver = EzReflect.classResolver

    @BeforeEach
    fun setUpClassResolver() {
        EzReflect.classResolver = object : ClassResolver {
            override fun classNamesOf(classLoader: ClassLoader): Sequence<String> =
                if (classLoader == this@ConditionalQueryTest.classLoader) {
                    sequenceOf(
                        ConditionalLoginActivity::class.java.name,
                        ConditionalLoginDialog::class.java.name,
                    )
                } else {
                    emptySequence()
                }
        }
    }

    @AfterEach
    fun restoreClassResolver() {
        EzReflect.classResolver = originalClassResolver
    }

    @Test
    fun `findClassIf can match class name and members`() {
        val result = findClassIf(classLoader) {
            findSingle()
            simpleNameContains("Activity")
            hasMethod {
                name("login")
                paramCount(2)
                returnType(Boolean::class.java)
            }
            hasField {
                name("token")
                type(String::class.java)
            }
        }

        assertSame(ConditionalLoginActivity::class.java, result)
    }

    @Test
    fun `findClassIf findSingle rejects multiple matches`() {
        assertThrows(SingleResultExpectedException::class.java) {
            findClassIf(classLoader) {
                findSingle()
                simpleNameContains("Login")
            }
        }
    }

    @Test
    fun `manual cache key can cache custom class filter`() {
        EzReflect.clearCache()
        var filterCalls = 0

        repeat(2) {
            val result = findClassIf(classLoader) {
                cacheKey("conditional-login-activity")
                filter {
                    filterCalls++
                    simpleName == "ConditionalLoginActivity"
                }
            }

            assertSame(ConditionalLoginActivity::class.java, result)
        }

        assertEquals(1, filterCalls)
    }

    @Test
    fun `findMethod findSingle rejects multiple matches`() {
        assertThrows(SingleResultExpectedException::class.java) {
            ConditionalMethodTarget::class.java.findMethod {
                findSingle()
                name("sameName")
            }
        }
    }
}

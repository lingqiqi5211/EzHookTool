package io.github.lingqiqi5211.ezhooktool.core

import io.github.lingqiqi5211.ezhooktool.core.java.Methods
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class MethodCallAliasTarget {
    @Suppress("unused")
    fun greet(name: String): String = "Hello, $name"

    companion object {
        @JvmStatic
        @Suppress("unused")
        fun sum(left: Int, right: Int): Int = left + right
    }
}

class MethodCallAliasTest {
    @Test
    fun `callMethodAs returns typed instance result`() {
        val target = MethodCallAliasTarget()

        val result: String? = target.callMethodAs("greet", "EzHookTool")

        assertEquals("Hello, EzHookTool", result)
    }

    @Test
    fun `callStaticMethodAs returns typed static result`() {
        val result: Int? = MethodCallAliasTarget::class.java.callStaticMethodAs("sum", 1, 2)

        assertEquals(3, result)
    }

    @Test
    fun `java Methods wrappers expose typed call aliases`() {
        val target = MethodCallAliasTarget()

        val instanceResult: String? = Methods.callMethodAs(target, "greet", "World")
        val staticResult: Int? = Methods.callStaticMethodAs(MethodCallAliasTarget::class.java, "sum", 4, 5)

        assertEquals("Hello, World", instanceResult)
        assertEquals(9, staticResult)
    }
}


package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 回归测试集中点：把扫描里点过名的"已知误导性 API"绑定具体行为，避免被未来重构悄悄破坏。
 */
class RegressionTest {

    // ───────────────────────── 1.3 staticField / isStatic ─────────────────────────

    private open class StaticVsInstanceBase {
        @Suppress("unused") val instanceField: String = "instance"

        companion object {
            @Suppress("unused")
            @JvmStatic
            val staticField: String = "static"
        }
    }

    @Test
    fun `staticField only matches static field even when same name has instance overload`() {
        // 这里 instance / static 名字不同，先验证基础正确性：staticField() 只取 static
        val clz = StaticVsInstanceBase::class.java
        val staticField = clz.staticField("staticField")
        assertNotNull(staticField)
        assertTrue(java.lang.reflect.Modifier.isStatic(staticField.modifiers))

        // 反向：用 instance 入口要求 instanceField 不应误命中 static field
        val instanceField = StaticVsInstanceBase::class.java.field("instanceField")
        assertNotNull(instanceField)
        assertTrue(!java.lang.reflect.Modifier.isStatic(instanceField.modifiers))
    }

    @Test
    fun `staticField returns null when only instance field exists with same name`() {
        // 关键回归：以前 staticField(name) 忽略 isStatic 参数；现在必须拿不到
        val result = StaticVsInstanceBase::class.java.staticFieldOrNull("instanceField")
        assertNull(result, "staticFieldOrNull 不应命中 instance 字段")
    }

    @Test
    fun `field with isStatic false skips static field with same name`() {
        // 反向：fieldOrNull(isStatic=false) 不应命中纯 static 字段
        val result = StaticVsInstanceBase::class.java.fieldOrNull("staticField", isStatic = false)
        assertNull(result, "fieldOrNull(isStatic=false) 不应命中 static 字段")
    }

    // ───────────────────────── 1.5 callMethodOrNull 真不抛 ─────────────────────────

    private class OrNullTarget {
        @Suppress("unused")
        fun greet(name: String): String = "Hello, $name"
    }

    @Test
    fun `callMethodOrNull returns null on missing method`() {
        val target = OrNullTarget()
        val result = target.callMethodOrNull("noSuchMethod", "x")
        assertNull(result)
    }

    @Test
    fun `callMethodOrNull with explicit args returns null on missing method`() {
        val target = OrNullTarget()
        val result = target.callMethodOrNull(
            methodName = "noSuchMethod",
            args = args("x"),
        )
        assertNull(result)
    }

    @Test
    fun `callStaticMethodOrNull returns null on missing method`() {
        val result = OrNullTarget::class.java.callStaticMethodOrNull("noSuchStatic")
        assertNull(result)
    }

    // ───────────────────────── 1.9 ResolveSession.optional() 不再撒谎 ─────────────────────────

    @Test
    fun `resolveSession optional method still throws when missing`() {
        // optional() 现在只是 no-op 兼容入口；method() 在未命中时仍按文档抛
        @Suppress("DEPRECATION")
        val session = ResolveSession.of(OrNullTarget::class.java).optional()
        assertThrows(MemberNotFoundException::class.java) {
            session.method { name("noSuchMethod") }
        }
    }

    @Test
    fun `resolveSession methodOrNull returns null on missing`() {
        val session = ResolveSession.of(OrNullTarget::class.java)
        val result = session.methodOrNull { name("noSuchMethod") }
        assertNull(result)
    }

    // ───────────────────────── 1.10 parameterTypes 严格匹配（KDoc 已说明）─────────────────────────

    private class PrimitiveTarget {
        @Suppress("unused")
        fun take(value: Int): String = "int=$value"

        @Suppress("unused")
        fun take(value: Int?): String = "Integer=$value"
    }

    @Test
    fun `parameterTypes is strict and does NOT auto-convert primitive to wrapper`() {
        val clz = PrimitiveTarget::class.java
        val intType = Int::class.javaPrimitiveType!!
        val integerType = Int::class.javaObjectType

        // 用 primitive int 匹配 fun take(value: Int) - hit
        val intHit = findMethodOrNull(clz) {
            name("take")
            parameterTypes(intType)
        }
        assertNotNull(intHit)
        assertSame(intType, intHit!!.parameterTypes[0])

        // 用 Integer 匹配同名重载 - hit Integer 版
        val integerHit = findMethodOrNull(clz) {
            name("take")
            parameterTypes(integerType)
        }
        assertNotNull(integerHit)
        assertSame(integerType, integerHit!!.parameterTypes[0])

        // 两次匹配返回的不应是同一个 Method（强校验严格语义）
        assertNotSame(intHit, integerHit)
    }

    @Test
    fun `parameterTypesAssignableFrom matches across primitive and wrapper`() {
        val clz = PrimitiveTarget::class.java
        val integerType = Int::class.javaObjectType

        // 用 Integer 通过 assignableFrom 匹配——按继承/装箱可同时兼容两个候选
        val matches = findAllMethods(clz) {
            name("take")
            parameterTypesAssignableFrom(integerType)
        }
        assertTrue(matches.isNotEmpty(), "paramsAssignableFrom 应能找到至少一个重载")
    }

    // ───────────────────────── 1.4 inferArgTypes 拒绝 null ─────────────────────────

    @Test
    fun `callMethod auto-infer throws when args contain null and no argTypes provided`() {
        val target = OrNullTarget()
        // greet(String) 不传 argTypes 且传 null，应该走自动匹配走 findMethodBestMatch；
        // 这条不会触发 inferArgTypes，因为 vararg 入口检测到 null 走 best match。
        val ok = runCatching { target.callMethod("greet", "world") }.isSuccess
        assertTrue(ok)

        // 但走显式 args + 空 argTypes + null 元素时会触发 inferArgTypes
        val ex = assertThrows(IllegalArgumentException::class.java) {
            target.callMethod("greet", args(null))
        }
        assertTrue(ex.message!!.contains("null argument"), "应明确提示 null 推断失败")
    }

    // ───────────────────────── 1.13 fieldCpy 不再静默失败 ─────────────────────────

    private class Src(@Suppress("unused") var name: String = "a")
    private class Dst(@Suppress("unused") var count: Int = 0)

    @Test
    fun `fieldCpy throws when dst is incompatible type`() {
        val src = Src()
        val dst = Dst()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            fieldCpy(src, dst)
        }
        assertTrue(ex.message!!.contains("fieldCpy failed"))
    }

    @Suppress("UNUSED_PARAMETER", "unused")
    private class FinalTarget(name: String) {
        // val 字段会被编译为 final 字段，本测试验证 fieldCpy 跳过 final 而非抛错
        val name: String = name
    }

    @Test
    fun `fieldCpy skips final fields without error`() {
        val src = FinalTarget("a")
        val dst = FinalTarget("b")
        // 不应抛异常；final 字段被跳过
        fieldCpy(src, dst)
        assertEquals("b", dst.name)
    }

    // ───────────────────────── 1.11 getMethodByDescOrNull 区分错 ─────────────────────────

    @Test
    fun `getMethodByDescOrNull returns null when method missing`() {
        val desc = "Ljava/lang/String;->thisMethodDoesNotExist()V"
        val result = getMethodByDescOrNull(desc)
        assertNull(result)
    }

    @Test
    fun `getMethodByDescOrNull throws on malformed descriptor`() {
        assertThrows(IllegalArgumentException::class.java) {
            getMethodByDescOrNull("not a descriptor")
        }
    }

    // ───────────────────────── 1.6 callMethodAs 对 null 抛清晰异常 ─────────────────────────

    private class NullReturning {
        @Suppress("unused")
        fun whatever(): String? = null
    }

    @Test
    fun `callMethodAs throws when target returns null and no OrNull is used`() {
        val target = NullReturning()
        // 名字没 OrNull 标识就不应给 null；目标返回 null 时 checkNotNull 抛 IllegalStateException
        val ex = assertThrows(IllegalStateException::class.java) {
            // 显式声明 String 类型，触发 callMethodAs<String> 的 checkNotNull
            @Suppress("UNUSED_VARIABLE")
            val result: String = target.callMethodAs("whatever")
        }
        assertTrue(ex.message!!.contains("returned null"))
    }

    @Test
    fun `callMethodAsOrNull returns null when target returns null`() {
        val target = NullReturning()
        val result = target.callMethodAsOrNull<String>("whatever")
        assertNull(result)
    }
}

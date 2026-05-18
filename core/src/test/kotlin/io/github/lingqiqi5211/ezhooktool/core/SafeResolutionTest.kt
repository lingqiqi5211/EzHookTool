package io.github.lingqiqi5211.ezhooktool.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private class SafeMemberTarget {
    @Suppress("unused")
    private val launcherTag = "tag"

    constructor()

    @Suppress("unused")
    constructor(value: Int)

    @Suppress("unused")
    fun setupViews() = "ok"
}

private class SafeResolveMethodTarget

private class BrokenClassLoader(parent: ClassLoader?) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name == "broken.BaseLauncher") {
            throw NoClassDefFoundError("Failed resolution of: Lcom/miui/newhome/view/gestureview/NewHomeView;")
        }
        return super.loadClass(name, resolve)
    }
}

class SafeResolutionTest {
    private class NestedClassTarget

    @Test
    fun `declared methods fallback returns hidden method list when direct access fails`() {
        val methods = resolveDeclaredMembersFallback(
            directAccess = {
                throw NoClassDefFoundError("Failed resolution of: Lcom/miui/newhome/view/gestureview/NewHomeView;")
            },
            hiddenAccess = { SafeMemberTarget::class.java.declaredMethods },
            emptyAccess = { emptyArray() },
        )

        assertTrue(methods.any { it.name == "setupViews" })
    }

    @Test
    fun `declared fields fallback returns hidden field list when direct access fails`() {
        val fields = resolveDeclaredMembersFallback(
            directAccess = {
                throw LinkageError("field linkage failed")
            },
            hiddenAccess = { SafeMemberTarget::class.java.declaredFields },
            emptyAccess = { emptyArray() },
        )

        assertTrue(fields.any { it.name == "launcherTag" })
    }

    @Test
    fun `declared constructors fallback returns hidden constructor list when direct access fails`() {
        val constructors = resolveDeclaredMembersFallback(
            directAccess = {
                throw NoClassDefFoundError("constructor linkage failed")
            },
            hiddenAccess = { SafeMemberTarget::class.java.declaredConstructors },
            emptyAccess = { emptyArray() },
        )

        assertTrue(constructors.any { it.parameterCount == 1 })
    }

    @Test
    fun `loadClassOrNull swallows recoverable linkage errors and lets fallback continue`() {
        val loader = BrokenClassLoader(SafeResolveMethodTarget::class.java.classLoader)

        assertNull(loadClassOrNull("broken.BaseLauncher", loader))
        assertEquals(
            SafeResolveMethodTarget::class.java,
            loadClassFirstOrNull("broken.BaseLauncher", SafeResolveMethodTarget::class.java.name, classLoader = loader),
        )
    }

    @Test
    fun `loadClassOrNull can fallback from dotted nested class name`() {
        val binaryName = NestedClassTarget::class.java.name
        val dottedName = binaryName.replaceRange(binaryName.lastIndexOf('$'), binaryName.lastIndexOf('$') + 1, ".")

        assertEquals(NestedClassTarget::class.java, loadClassOrNull(dottedName))
        assertEquals(NestedClassTarget::class.java, loadClassOrNull(binaryName))
    }

    @Test
    fun `lazyClass resolves when requested`() {
        val binaryName = NestedClassTarget::class.java.name
        val dottedName = binaryName.replaceRange(binaryName.lastIndexOf('$'), binaryName.lastIndexOf('$') + 1, ".")
        val target by lazyClass(dottedName)

        assertEquals(NestedClassTarget::class.java, target)
        assertNull(lazyClassOrNull("missing.LazyClassTarget").resolveOrNull())
    }

    @Test
    fun `member not found message includes query conditions`() {
        val error = assertThrows(MemberNotFoundException::class.java) {
            SafeMemberTarget::class.java.findMethod {
                name("missingMethod")
                params(String::class.java)
            }
        }

        assertTrue(error.message.orEmpty().contains("Condition: name=missingMethod, params=[java.lang.String]"))
    }
}

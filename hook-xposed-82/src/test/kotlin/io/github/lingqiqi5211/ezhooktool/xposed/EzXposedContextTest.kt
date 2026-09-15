package io.github.lingqiqi5211.ezhooktool.xposed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EzXposedContextTest {
    private val cache = EzXposed::class.java.getDeclaredField("appContextValue").apply { isAccessible = true }
    private var original: Any? = null

    @BeforeEach
    fun saveState() {
        original = cache.get(null)
        cache.set(null, null)
    }

    @AfterEach
    fun restoreState() {
        cache.set(null, original)
    }

    @Test
    fun defaultContextUsesApplicationAndForcePreservesExactObject() {
        val app = Application()
        val component =
            object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = app
            }
        EzXposed.initAppContext(component)
        assertSame(app, EzXposed.appContextOrNull)
        EzXposed.initAppContext(component, force = true)
        assertSame(component, EzXposed.appContextOrNull)
    }

    @Test
    fun missingApplicationDoesNotRetainComponentOrPreventLifecycleFill() {
        val component =
            object : ContextWrapper(null) {
                override fun getApplicationContext(): Context? = null
            }
        assertThrows<IllegalStateException> { EzXposed.initAppContext(component) }
        assertNull(cache.get(null))
        val app = Application()
        EzXposed.cacheApplicationContextFromLifecycle(app)
        EzXposed.initAppContext(component)
        assertSame(app, EzXposed.appContextOrNull)
    }
}

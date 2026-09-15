package io.github.lingqiqi5211.ezhooktool.xposed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.core.EzLogger
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.replaceWith
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.unhookAll
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ResourcesPlatform
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.ref.WeakReference
import java.util.function.Consumer

class EzXposedLifecycleTest {
    @Test
    fun immediateTargetReadyCallbackDoesNotLeakLoggerFailure() {
        val dispatcher = field("targetReadyDispatcher").get(null) as TargetReadyDispatcher
        dispatcher.reset()
        dispatcher.start()
        dispatcher.run(false, null) { fail("No callback was registered") }
        val previous = EzReflect.logger
        try {
            EzReflect.logger = object : EzLogger {
                override fun debug(tag: String, msg: String) = Unit
                override fun warn(tag: String, msg: String) = Unit
                override fun error(tag: String, msg: String, t: Throwable?) { error("logger failed") }
            }
            assertDoesNotThrow { EzXposed.onTargetReady { error("callback failed") } }
            assertEquals(TargetReadyState.SUCCEEDED, EzXposed.targetReadyState)
        } finally {
            EzReflect.logger = previous
        }
    }

    private val fields =
        listOf(
            "base",
            "appContextValue",
            "moduleResourcesInitialized",
            "currentHotReloadParam",
            "hotReloadFailure",
            "modulePath",
            "moduleRes",
            "processName",
            "isSystemServer",
            "packageName",
            "targetSnapshot",
            "automaticHookBatch",
            "automaticHookIds",
            "moduleEntry",
            "hotReloadEnabled",
        )
    private lateinit var original: Map<String, Any?>

    private fun field(name: String) = EzXposed::class.java.getDeclaredField(name).apply { isAccessible = true }

    @BeforeEach
    fun saveState() {
        original = fields.associateWith { field(it).get(null) }
        field("appContextValue").set(null, null)
        field("hotReloadFailure").set(null, null)
    }

    @AfterEach
    fun restoreState() {
        original.forEach { (name, value) -> field(name).set(null, value) }
        (field("targetReadyDispatcher").get(null) as TargetReadyDispatcher).reset()
    }

    @Test
    fun deoptimizePreservesFalseAndTrue() {
        val executable = String::class.java.getDeclaredMethod("length")
        for (result in listOf(false, true)) {
            EzXposed.base =
                fakeXposed { method, args ->
                    assertEquals("deoptimize", method.name)
                    assertSame(executable, args[0])
                    result
                }
            assertEquals(result, EzXposed.deoptimize(executable))
            assertEquals(result, EzXposed.deoptimizeOrThrow(executable))
        }
    }

    @Test
    fun deoptimizeCanPreserveExceptionCause() {
        val executable = String::class.java.getDeclaredMethod("length")
        val cause = IllegalStateException("framework failure")
        EzXposed.base = fakeXposed { _, _ -> throw cause }
        assertFalse(EzXposed.deoptimize(executable))
        assertSame(cause, assertThrows<IllegalStateException> { EzXposed.deoptimizeOrThrow(executable) })
        field("base").set(null, null)
        assertFalse(EzXposed.deoptimize(executable))
        assertThrows<IllegalStateException> { EzXposed.deoptimizeOrThrow(executable) }
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
    fun unavailableApplicationIsNotCachedAndLifecycleCanFillItLater() {
        val component =
            object : ContextWrapper(null) {
                override fun getApplicationContext(): Context? = null
            }
        assertThrows<IllegalStateException> { EzXposed.initAppContext(component) }
        assertNull(field("appContextValue").get(null))
        val app = Application()
        EzXposed.cacheApplicationContextFromLifecycle(app)
        EzXposed.initAppContext(component)
        assertSame(app, EzXposed.appContextOrNull)
    }

    @Test
    fun repeatedModuleInitializationDoesNotRecreateInitializedResources() {
        val info =
            ApplicationInfo().apply {
                packageName = "test.module"
                sourceDir = "/does-not-exist.apk"
            }
        val base =
            fakeXposed { method, _ ->
                if (method.name == "getModuleApplicationInfo") info else error(method.name)
            }
        val param =
            fake<XposedModuleInterface.ModuleLoadedParam> { method, _ ->
                when (method.name) {
                    "getProcessName" -> "test.process"
                    "isSystemServer" -> false
                    else -> error(method.name)
                }
            }
        EzXposed.base = base
        field("currentHotReloadParam").set(null, null)
        field("moduleResourcesInitialized").setBoolean(null, true)
        repeat(3) { EzXposed.initOnModuleLoaded(base, param) }
        assertEquals("test.process", EzXposed.processName)
        assertTrue(field("moduleResourcesInitialized").getBoolean(null))
        field("moduleResourcesInitialized").setBoolean(null, false)
        repeat(2) {
            assertThrows<Throwable> { EzXposed.initOnModuleLoaded(base, param) }
            assertFalse(field("moduleResourcesInitialized").getBoolean(null))
        }
    }

    @Test
    fun restoreCallbackFailuresRespectPublicationBoundary() {
        val originalLoader = EzReflect.classLoader
        val wasInitialized = EzReflect.isInitialized
        try {
            for (phase in listOf("extra", "oldHooks", "targetReady", "replace", "unhook", "async")) {
                (field("targetReadyDispatcher").get(null) as TargetReadyDispatcher).reset()
                field("hotReloadFailure").set(null, null)
                field("automaticHookBatch").set(null, null)
                val info =
                    ApplicationInfo().apply {
                        packageName = "test"
                        sourceDir = "/test.apk"
                    }
                val base =
                    fakeXposed { method, _ ->
                        if (method.name == "getModuleApplicationInfo") info else error(method.name)
                    }
                val saved =
                    TargetSnapshot("test", "test", javaClass.classLoader!!, null, false)
                        .toCrossGenArray(emptyArray())
                val param =
                    fake<XposedModuleInterface.HotReloadedParam> { method, _ ->
                        when (method.name) {
                            "getSavedInstanceState" -> saved
                            "getOldHookHandles" -> emptyList<Any>()
                            "getProcessName" -> "test"
                            "isSystemServer" -> false
                            else -> error(method.name)
                        }
                    }
                EzXposed.base = base
                XposedApiCompat.resolve(base)
                field("currentHotReloadParam").set(null, WeakReference(param))
                field("moduleResourcesInitialized").setBoolean(null, true)
                val cause = IllegalArgumentException(phase)
                var undone = false
                EzXposed.onTargetReady { if (phase == "targetReady") throw cause }
                val failure =
                    assertThrows<Throwable> {
                        EzXposed.restoreHotReloaded(
                            base,
                            param,
                            onOldHooks = if (phase == "oldHooks") Consumer { throw cause } else null,
                            onExtra =
                                Consumer {
                                    val pending =
                                        EzResources::class.java
                                            .getDeclaredField("pendingSwap")
                                            .apply { isAccessible = true }
                                            .get(null)
                                    assertNotNull(pending, "Even empty saved resources must start a journal")
                                    val journal =
                                        pending.javaClass
                                            .getDeclaredField("journal")
                                            .apply { isAccessible = true }
                                            .get(pending) as ResourceReloadJournal
                                    journal.record { undone = true }
                                    if (phase == "async") {
                                        val rejected =
                                            assertThrows<IllegalStateException> {
                                                EzResources.inject(Resources(null, null, null), onMainLooper = true)
                                            }
                                        assertTrue(rejected.message!!.contains("synchronous"))
                                        throw cause
                                    }
                                    if (phase == "replace" || phase == "unhook") {
                                        lateinit var handle: XposedInterface.HookHandle
                                        handle =
                                            fake { method, _ ->
                                                when (method.name) {
                                                    "getExecutable" -> String::class.java.getDeclaredMethod("length")
                                                    "replaceHook" -> handle
                                                    "unhook" -> null
                                                    else -> error(method.name)
                                                }
                                            }
                                        if (phase == "replace") handle.replaceWith { null } else listOf(handle).unhookAll()
                                        throw cause
                                    }
                                    if (phase == "extra") throw cause
                                },
                        )
                    }
                if (phase in listOf("oldHooks", "replace", "unhook")) {
                    assertFalse(undone)
                    assertSame(cause, failure.cause)
                } else {
                    assertTrue(undone)
                    assertSame(cause, failure)
                }
                assertSame(failure, field("hotReloadFailure").get(null))
                assertThrows<IllegalStateException> { EzXposed.restoreHotReloaded(base, param, null, null) }
            }
        } finally {
            if (wasInitialized) EzReflect.init(originalLoader) else EzReflect.reset()
            EzResources.commitHotReload()
        }
    }

    @Test
    fun resourceRestoreFalseIsReportedBeforePublication() {
        field("modulePath").set(null, null)
        try {
            val failure =
                assertThrows<IllegalStateException> {
                    EzResources.restoreFromHotReload(arrayOf(listOf(Resources(null, null, null)), null))
                }
            assertTrue(failure.message!!.contains("Failed to restore module resources"))
        } finally {
            EzResources.rollbackHotReload()
        }
    }

    @Test
    fun resourceHookUsesAnonymousHandleOn101AndStableKeyOn102() {
        val method = String::class.java.getDeclaredMethod("length")
        for (version in listOf(101, 102)) {
            val ids = mutableListOf<String>()
            var installed = 0
            val handle = fake<XposedInterface.HookHandle> { _, _ -> error("Unused handle") }
            lateinit var builder: XposedInterface.HookBuilder
            builder =
                fake { call, args ->
                    when (call.name) {
                        "setPriority", "setExceptionMode" -> {
                            builder
                        }

                        "setId" -> {
                            ids += args[0] as String
                            builder
                        }

                        "intercept" -> {
                            installed++
                            handle
                        }

                        else -> {
                            error(call.name)
                        }
                    }
                }
            val base =
                fake<XposedInterface> { call, args ->
                    when (call.name) {
                        "getApiVersion" -> {
                            version
                        }

                        "hook" -> {
                            assertSame(method, args[0])
                            builder
                        }

                        else -> {
                            error(call.name)
                        }
                    }
                }
            EzXposed.base = base
            XposedApiCompat.resolve(base)
            ResourcesPlatform.hookBefore(method, "resource-key") {}
            assertEquals(1, installed)
            assertEquals(if (version == 101) emptyList() else listOf("resource-key"), ids)
        }
    }

    @Test
    fun savedStateRoundTripPreservesExtraAndOptionalResources() {
        val snapshot = TargetSnapshot("pkg", "process", javaClass.classLoader!!, null, false)
        val extra = arrayOf<Any?>("state")
        val resources = arrayOf<Any?>(emptyList<Any>(), null)
        val saved = snapshot.toCrossGenArray(extra, resources)
        assertEquals(snapshot, TargetSnapshot.tryRestore(saved))
        assertSame(extra, TargetSnapshot.restoreExtra(saved))
        assertSame(resources, TargetSnapshot.restoreResources(saved))
        assertEquals(snapshot, TargetSnapshot.tryRestore(saved.copyOf(8)))
        assertNull(TargetSnapshot.restoreResources(saved.copyOf(8)))
        assertNull(TargetSnapshot.tryRestore(arrayOf("invalid")))
    }
}

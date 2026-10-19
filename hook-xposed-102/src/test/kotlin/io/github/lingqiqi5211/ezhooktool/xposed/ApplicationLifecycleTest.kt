package io.github.lingqiqi5211.ezhooktool.xposed

import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.EzLogger
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ApplicationAttachRegistry
import io.github.lingqiqi5211.ezhooktool.xposed.internal.ApplicationLifecycle
import io.github.lingqiqi5211.ezhooktool.xposed.internal.HookDiagnostics
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ApplicationLifecycleTest {
    private val throwingLogger =
        object : EzLogger {
            override fun debug(
                tag: String,
                msg: String,
            ) = error("logger.debug failed")

            override fun warn(
                tag: String,
                msg: String,
            ) = error("logger.warn failed")

            override fun error(
                tag: String,
                msg: String,
                t: Throwable?,
            ) = error("logger.error failed")
        }

    private var originalHookInstalled = false
    private var originalHookHandle: Any? = null
    private var originalAppContext: Any? = null
    private var originalBase: Any? = null
    private var originalSupported: Int = 0
    private var originalResolvedApiVersion: Int = 0
    private lateinit var originalLogger: EzLogger

    @BeforeEach
    fun saveState() {
        originalHookInstalled = lifecycleField("hookInstalled").getBoolean(ApplicationLifecycle)
        originalHookHandle = lifecycleField("hookHandle").get(ApplicationLifecycle)
        originalAppContext = ezField("appContextValue").get(null)
        originalBase = ezField("base").get(null)
        originalSupported = compatField("supported").getInt(XposedApiCompat)
        originalResolvedApiVersion = compatField("resolvedApiVersion").getInt(XposedApiCompat)
        originalLogger = EzReflect.logger
        resetLifecycle()
    }

    @AfterEach
    fun restoreState() {
        EzReflect.logger = originalLogger
        clearRegistry()
        lifecycleField("hookInstalled").setBoolean(ApplicationLifecycle, originalHookInstalled)
        lifecycleField("hookHandle").set(ApplicationLifecycle, originalHookHandle)
        ezField("appContextValue").set(null, originalAppContext)
        ezField("base").set(null, originalBase)
        compatField("supported").setInt(XposedApiCompat, originalSupported)
        compatField("resolvedApiVersion").setInt(XposedApiCompat, originalResolvedApiVersion)
    }

    @Test
    fun attachHookUsesAnonymousHandleOn101AndStableInternalKeyOn102() {
        val target = String::class.java.getDeclaredMethod("length")
        for (version in listOf(101, 102)) {
            resetLifecycle()
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
                            assertSame(target, args[0])
                            builder
                        }

                        else -> {
                            error(call.name)
                        }
                    }
                }
            EzXposed.base = base
            XposedApiCompat.resolve(base)

            assertSame(handle, ApplicationLifecycle.installAttachHook(target))

            assertEquals(1, installed)
            assertEquals(
                if (version == 101) emptyList() else listOf("ezhooktool.internal.application-attach"),
                ids,
            )
        }
    }

    @Test
    fun callbackFailureWithThrowingLoggerDoesNotStopLaterCallbacks() {
        skipInstall()
        val calls = mutableListOf<String>()
        // 直接登记到 registry：单元测试里没有 ActivityThread，appContextOrNull 探针不参与本用例。
        ApplicationAttachRegistry.register(
            {
                calls += "first"
                error("first callback failed")
            },
            persistent = false,
        ) { null }
        ApplicationAttachRegistry.register({ calls += "second" }, persistent = false) { null }
        EzReflect.logger = throwingLogger

        assertDoesNotThrow { dispatch(Application()) }

        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun lateRegistrationIsProtectedFromThrowingLogger() {
        skipInstall()
        ezField("appContextValue").set(null, Application())
        EzReflect.logger = throwingLogger

        assertDoesNotThrow { ApplicationLifecycle.register { error("late callback failed") } }
        var called = false
        assertDoesNotThrow { ApplicationLifecycle.register { called = true } }

        assertTrue(called)
    }

    @Test
    fun onceSubscriptionIsConsumedEvenWhenCallbackThrows() {
        skipInstall()
        ezField("appContextValue").set(null, Application())
        EzReflect.logger = throwingLogger
        val calls = AtomicInteger()

        val token =
            ApplicationLifecycle.registerOnce {
                calls.incrementAndGet()
                error("once callback failed")
            }

        assertEquals(1, calls.get())
        assertNull((token as ApplicationAttachRegistry.Subscription).entry.callback)
        assertEquals(0, registryEntries().size)

        assertDoesNotThrow { dispatch(Application()) }
        assertEquals(1, calls.get())
    }

    @Test
    fun lateRegistrationOfOnceSubscriptionFiresOnceAndReleasesToken() {
        skipInstall()
        ezField("appContextValue").set(null, Application())
        val calls = AtomicInteger()

        val token = ApplicationLifecycle.registerOnce { calls.incrementAndGet() }

        assertEquals(1, calls.get())
        assertNull((token as ApplicationAttachRegistry.Subscription).entry.callback)
        assertEquals(0, registryEntries().size)
        dispatch(Application())
        assertEquals(1, calls.get())
    }

    @Test
    fun subscriptionEntryPointsFailWithoutLeavingCallbackWhenAttachHookIsUnavailable() {
        // 单元测试的 android.jar stub 没有隐藏的 Application.attach(Context)，安装必然失败。
        EzReflect.logger = throwingLogger

        assertThrows(IllegalStateException::class.java) { ApplicationLifecycle.registerOnce { } }
        assertThrows(IllegalStateException::class.java) { ApplicationLifecycle.subscribe { } }

        assertFalse(lifecycleField("hookInstalled").getBoolean(ApplicationLifecycle))
        assertEquals(0, registryEntries().size)
    }

    @Test
    fun registrationDuringDispatchFiresExactlyOnce() {
        skipInstall()
        val nestedCalls = AtomicInteger()
        val registered = AtomicBoolean(false)
        ApplicationAttachRegistry.register(
            {
                if (registered.compareAndSet(false, true)) {
                    ApplicationLifecycle.registerOnce { nestedCalls.incrementAndGet() }
                }
            },
            persistent = true,
        ) { null }

        dispatch(Application())
        assertEquals(1, nestedCalls.get())

        dispatch(Application())
        assertEquals(1, nestedCalls.get())
    }

    @Test
    fun cancelledSubscriptionStopsLaterDispatch() {
        skipInstall()
        ezField("appContextValue").set(null, Application())
        val calls = AtomicInteger()

        val token = ApplicationLifecycle.subscribe { calls.incrementAndGet() }
        assertEquals(1, calls.get())

        dispatch(Application())
        assertEquals(2, calls.get())

        token.cancel()
        token.cancel()
        dispatch(Application())
        assertEquals(2, calls.get())
    }

    @Test
    fun lifecycleDiagnosticsNeverEscapeThrowingLogger() {
        EzReflect.logger = throwingLogger
        assertDoesNotThrow { HookDiagnostics.error("lifecycle", "message", IllegalStateException("cause")) }
        assertDoesNotThrow { HookDiagnostics.warn("lifecycle", "message") }
        assertDoesNotThrow { HookDiagnostics.debug("lifecycle", "message") }
    }

    private fun resetLifecycle() {
        clearRegistry()
        lifecycleField("hookInstalled").setBoolean(ApplicationLifecycle, false)
        lifecycleField("hookHandle").set(ApplicationLifecycle, null)
        ezField("appContextValue").set(null, null)
    }

    private fun skipInstall() {
        lifecycleField("hookInstalled").setBoolean(ApplicationLifecycle, true)
    }

    private fun dispatch(context: Context) {
        ApplicationLifecycle::class.java
            .getDeclaredMethod("dispatch", Context::class.java)
            .apply { isAccessible = true }
            .invoke(ApplicationLifecycle, context)
    }

    private fun clearRegistry() {
        registryEntries().clear()
        registryField("sequence").setLong(ApplicationAttachRegistry, 0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun registryEntries() = registryField("entries").get(ApplicationAttachRegistry) as MutableList<Any>

    private fun registryField(name: String) = ApplicationAttachRegistry::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun lifecycleField(name: String) = ApplicationLifecycle::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun ezField(name: String) = EzXposed::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun compatField(name: String) = XposedApiCompat::class.java.getDeclaredField(name).apply { isAccessible = true }
}

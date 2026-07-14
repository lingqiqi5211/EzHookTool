package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Executable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections

class HookReloadBatchTest {
    @Test
    fun unkeyedHooksAreCombinedIntoOneStablePhysicalHook() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)
        val events = mutableListOf<String>()

        HookReloadBatch("batch-test", registry.xposed).install(Runnable {
            target.createHook {
                intercept { chain ->
                    events += "first-before"
                    chain.proceed().also { events += "first-after" }
                }
            }
            target.createHook {
                intercept { chain ->
                    events += "second-before"
                    chain.proceed().also { events += "second-after" }
                }
            }
        })

        assertEquals(1, registry.installed.size)
        val physical = registry.installed.single()
        assertTrue(physical.id?.startsWith("ezhooktool.batch.v1:") == true)
        val result = physical.hooker.intercept(
            TestChain(target, SampleTarget(), arrayOf("value")) {
                _, args ->
                events += "origin:${args[0]}"
                "result"
            },
        )
        assertEquals("result", result)
        assertEquals(
            listOf("first-before", "second-before", "origin:value", "second-after", "first-after"),
            events,
        )
    }

    @Test
    fun matchingAggregatedIdReplacesOldPhysicalHookBeforeCleanup() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)

        HookReloadBatch("batch-test", registry.xposed, true).install(Runnable {
            target.createHook { intercept { it.proceed() } }
        })
        val oldHandle = registry.installed.single()

        val reloaded = HookReloadBatch("batch-test", registry.xposed, true)
        reloaded.captureOldHooks(listOf(oldHandle))
        reloaded.install(Runnable {
            target.createHook { intercept { it.proceed() } }
            target.createHook { intercept { it.proceed() } }
        })
        val result = reloaded.finishHotReload()

        assertEquals(1, result.atomicallyReplacedHookCount)
        assertEquals(0, result.removedOldHookCount)
        assertFalse(oldHandle.valid)
        assertEquals(0, oldHandle.unhookCalls)
    }

    @Test
    fun strictReloadRejectsTopologyChangesBeforePublishingHooks() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)

        HookReloadBatch("batch-test", registry.xposed, true).install(Runnable {
            target.createHook { intercept { it.proceed() } }
        })
        val oldHandle = registry.installed.single()

        val reloaded = HookReloadBatch("batch-test", registry.xposed, true)
        reloaded.captureOldHooks(listOf(oldHandle))
        assertThrows(IllegalStateException::class.java) {
            reloaded.install(Runnable {
                target.createHook {
                    priority(XposedInterface.PRIORITY_DEFAULT + 1)
                    intercept { it.proceed() }
                }
            })
        }

        assertTrue(oldHandle.valid)
        assertEquals(1, registry.installed.size)
    }

    @Test
    fun hookOutsideBatchGetsFallbackAutomaticId() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)

        target.createHook { intercept { it.proceed() } }

        assertEquals(1, registry.installed.size)
        assertTrue(registry.installed.single().id?.startsWith("ezhooktool.auto.v1:") == true)
    }

    @Test
    fun automaticIdAllocatorIsStableForTheSameRegistrationSequence() {
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)
        val first = AutomaticHookIdAllocator("batch-test")
        val second = AutomaticHookIdAllocator("batch-test")

        val firstId = first.allocate(
            target,
            XposedInterface.PRIORITY_DEFAULT,
            XposedInterface.ExceptionMode.DEFAULT,
        )
        val secondId = second.allocate(
            target,
            XposedInterface.PRIORITY_DEFAULT,
            XposedInterface.ExceptionMode.DEFAULT,
        )
        val nextId = first.allocate(
            target,
            XposedInterface.PRIORITY_DEFAULT,
            XposedInterface.ExceptionMode.DEFAULT,
        )

        assertEquals(firstId, secondId)
        assertFalse(firstId == nextId)
    }

    @Test
    fun failedPreparationDoesNotPublishAnyAggregatedHook() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)
        val batch = HookReloadBatch("batch-test", registry.xposed)

        assertThrows(IllegalStateException::class.java) {
            batch.install(Runnable {
                target.createHook { intercept { it.proceed() } }
                throw IllegalStateException("initialization failed")
            })
        }

        assertTrue(registry.installed.isEmpty())
        assertNotNull(batch.hotReloadBlockReason)
    }

    @Test
    fun failedPreparationDoesNotPublishExplicitIdHook() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)
        val batch = HookReloadBatch("batch-test", registry.xposed)

        assertThrows(IllegalStateException::class.java) {
            batch.install(Runnable {
                target.createHook {
                    reloadKey("stable-test-hook")
                    intercept { it.proceed() }
                }
                throw IllegalStateException("initialization failed")
            })
        }

        assertTrue(registry.installed.isEmpty())
        assertNotNull(batch.hotReloadBlockReason)
    }

    @Test
    fun explicitlyDisabledAutomaticIdIsRejectedBeforeReloadPublishesHooks() {
        val registry = RecordingXposed()
        EzXposed.base = registry.xposed
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)

        HookReloadBatch("batch-test", registry.xposed).install(Runnable {
            target.createHook { intercept { it.proceed() } }
        })
        val oldHandle = registry.installed.single()

        val reloaded = HookReloadBatch("batch-test", registry.xposed)
        reloaded.captureOldHooks(listOf(oldHandle))
        assertThrows(IllegalStateException::class.java) {
            reloaded.install(Runnable {
                target.createHook {
                    id(null)
                    intercept { it.proceed() }
                }
            })
        }

        assertTrue(oldHandle.valid)
        assertEquals(1, registry.installed.size)
    }

    @Test
    fun unkeyedOldHandleIsRejectedBeforeNewHooksAreInstalled() {
        val registry = RecordingXposed()
        val target = SampleTarget::class.java.getDeclaredMethod("invoke", String::class.java)
        val oldHandle = registry.xposed.hook(target)
            .intercept(XposedInterface.Hooker { it.proceed() })
        val batch = HookReloadBatch("batch-test", registry.xposed)

        assertThrows(IllegalStateException::class.java) {
            batch.captureOldHooks(listOf(oldHandle))
        }
        assertTrue(oldHandle is RecordingXposed.FakeHandle && oldHandle.valid)
    }

    private class SampleTarget {
        fun invoke(value: String): String = value
    }

    private class RecordingXposed {
        val installed = mutableListOf<FakeHandle>()
        private val activeByIdentity = LinkedHashMap<HookIdentity, FakeHandle>()

        val xposed: XposedInterface = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "hook" -> FakeHookBuilder(this, args!![0] as Executable)
                    "toString" -> "RecordingXposed"
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as XposedInterface

        private fun install(
            executable: Executable,
            priority: Int,
            exceptionMode: XposedInterface.ExceptionMode,
            id: String?,
            hooker: XposedInterface.Hooker,
        ): FakeHandle {
            val identity = id?.let { HookIdentity(executable, it) }
            identity?.let { activeByIdentity.remove(it)?.valid = false }
            return FakeHandle(this, executable, priority, exceptionMode, id, hooker).also { handle ->
                installed += handle
                identity?.let { activeByIdentity[it] = handle }
            }
        }

        private fun unhook(handle: FakeHandle) {
            handle.hookId?.let { activeByIdentity.remove(HookIdentity(handle.executable, it)) }
        }

        private class FakeHookBuilder(
            private val owner: RecordingXposed,
            private val executable: Executable,
        ) : XposedInterface.HookBuilder {
            private var priority = XposedInterface.PRIORITY_DEFAULT
            private var exceptionMode = XposedInterface.ExceptionMode.DEFAULT
            private var id: String? = null

            override fun setPriority(priority: Int): XposedInterface.HookBuilder = apply {
                this.priority = priority
            }

            override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder = apply {
                exceptionMode = mode
            }

            override fun setId(id: String?): XposedInterface.HookBuilder = apply {
                this.id = id
            }

            override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
                owner.install(executable, priority, exceptionMode, id, hooker)
        }

        class FakeHandle(
            private val owner: RecordingXposed,
            private val target: Executable,
            private val priority: Int,
            private val exceptionMode: XposedInterface.ExceptionMode,
            val hookId: String?,
            val hooker: XposedInterface.Hooker,
        ) : XposedInterface.HookHandle {
            var valid = true
            var unhookCalls = 0

            override fun getExecutable(): Executable = target

            override fun getId(): String? = hookId

            override fun unhook() {
                if (!valid) return
                valid = false
                unhookCalls++
                owner.unhook(this)
            }

            override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                check(valid) { "Hook handle is no longer valid." }
                valid = false
                return owner.install(target, priority, exceptionMode, hookId, hooker)
            }
        }

        private data class HookIdentity(
            val executable: Executable,
            val id: String,
        )
    }

    private class TestChain(
        private val target: Executable,
        private val receiver: Any?,
        private val callArgs: Array<Any?>,
        private val origin: (Any?, Array<Any?>) -> Any?,
    ) : XposedInterface.Chain {
        override fun getExecutable(): Executable = target

        override fun getThisObject(): Any? = receiver

        override fun getArgs(): List<Any?> = Collections.unmodifiableList(callArgs.asList())

        override fun getArg(index: Int): Any? = callArgs[index]

        override fun proceed(): Any? = origin(receiver, callArgs.copyOf())

        override fun proceed(args: Array<out Any?>): Any? = origin(receiver, copyCallArgs(args))

        override fun proceedWith(thisObject: Any): Any? = origin(thisObject, callArgs.copyOf())

        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? =
            origin(thisObject, copyCallArgs(args))

        private fun copyCallArgs(args: Array<out Any?>): Array<Any?> = Array(args.size) { args[it] }
    }
}

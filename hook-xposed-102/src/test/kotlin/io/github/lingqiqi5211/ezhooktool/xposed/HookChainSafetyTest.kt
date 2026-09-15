package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.core.EzLogger
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.common.*
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.buildHooker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HookChainSafetyTest {
    private val target = String::class.java.getDeclaredMethod("substring", Int::class.javaPrimitiveType)

    @Test
    fun recursiveInvocationKeepsOuterArgumentsAndReverseAfterState() {
        val events = mutableListOf<String>()
        lateinit var hooks: HookChain
        fun call(depth: Int): Any? = hooks.invoke(fake<XposedInterface.Chain> { method, args ->
            when (method.name) {
                "getExecutable" -> target
                "getThisObject" -> null
                "getArgs" -> listOf(depth)
                "proceed" -> {
                    assertEquals(depth, (args[0] as Array<*>)[0])
                    events += "original$depth"
                    if (depth == 0) 0 else call(depth - 1) as Int + depth
                }
                else -> error(method.name)
            }
        })
        hooks = HookChain(listOf(
            BeforeChainStage { events += "before${it.args[0]}" },
            AfterChainStage { events += "after1:${it.args[0]}:${it.result}" },
            AfterChainStage { events += "after2:${it.args[0]}:${it.result}" },
        ))
        assertEquals(3, call(2))
        assertEquals(listOf(
            "before2", "original2", "before1", "original1", "before0", "original0",
            "after2:0:0", "after1:0:0", "after2:1:1", "after1:1:1", "after2:2:3", "after1:2:3",
        ), events)
    }

    @Test
    fun throwingLoggerDoesNotInterruptBeforeOrRepeatAfterFallback() {
        val previousLogger = EzReflect.logger
        val previousSafe = EzXposed.safeMode
        try {
            EzXposed.safeMode = true
            EzReflect.logger =
                object : EzLogger {
                    override fun debug(
                        tag: String,
                        msg: String,
                    ) = Unit

                    override fun warn(
                        tag: String,
                        msg: String,
                    ) = Unit

                    override fun error(
                        tag: String,
                        msg: String,
                        t: Throwable?,
                    ) {
                        error("logger failed")
                    }
                }
            val stages =
                listOf(
                    BeforeChainStage {
                        it.args[0] = 9
                        it.result = "changed"
                        error("before failed")
                    },
                    AfterChainStage {
                        it.result = "changed"
                        error("after failed")
                    },
                )
            for (stage in stages) {
                var calls = 0
                val chain =
                    fake<XposedInterface.Chain> { method, args ->
                        when (method.name) {
                            "getExecutable" -> {
                                target
                            }

                            "getThisObject" -> {
                                "original"
                            }

                            "getArgs" -> {
                                listOf(1)
                            }

                            "proceedWith" -> {
                                calls++
                                assertEquals(1, (args[1] as Array<*>)[0])
                                "result"
                            }

                            else -> {
                                error(method.name)
                            }
                        }
                    }
                assertEquals("result", buildHooker(target, listOf(stage)).intercept(chain))
                assertEquals(1, calls)
            }
        } finally {
            EzReflect.logger = previousLogger
            EzXposed.safeMode = previousSafe
        }
    }

    @Test
    fun preclassifiedStagesKeepBeforeAroundAndReverseAfterOrder() {
        val events = mutableListOf<String>()
        val stages =
            listOf(
                BeforeChainStage { events += "before1" },
                AfterChainStage { events += "after1" },
                BeforeChainStage { events += "before2" },
                ChainStage { _, proceed ->
                    events += "around-in"
                    proceed()
                    events += "around-out"
                },
                AfterChainStage { events += "after2" },
            )
        val hooks = HookChain(stages)
        val chain =
            fake<XposedInterface.Chain> { method, _ ->
                when (method.name) {
                    "getExecutable" -> {
                        target
                    }

                    "getThisObject" -> {
                        null
                    }

                    "getArgs" -> {
                        listOf(1)
                    }

                    "proceed" -> {
                        events += "original"
                        7
                    }

                    else -> {
                        error(method.name)
                    }
                }
            }
        repeat(2) {
            events.clear()
            assertEquals(7, hooks.invoke(chain))
            assertEquals(listOf("before1", "before2", "around-in", "original", "around-out", "after2", "after1"), events)
        }
    }
}

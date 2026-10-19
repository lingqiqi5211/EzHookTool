package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 连续两代（三代 entry）的 [HookReloadBatch] 迁移：只有未被重新声明的旧 handle 会被 unhook，
 * 已被 framework 原子替换的旧 handle 一次都不能再碰，计数逐代准确。
 */
class HookReloadGenerationChainTest {
    private val executable = String::class.java.getDeclaredMethod("length")
    private val xposed = fakeXposed()
    private val hooker = XposedInterface.Hooker { null }

    /** 模拟 framework 的 live hook 表：相同 ID 的第二次安装是原子替换。 */
    private val live = LinkedHashMap<String, XposedInterface.HookHandle>()
    private val unhooked = mutableListOf<String>()
    private lateinit var compatSnapshot: XposedApiCompatSnapshot

    @BeforeEach
    fun resolveApi() {
        compatSnapshot = XposedApiCompatSnapshot()
        XposedApiCompat.resolve(xposed)
    }

    @AfterEach
    fun restoreApi() {
        if (::compatSnapshot.isInitialized) compatSnapshot.close()
    }

    @Test
    fun twoConsecutiveGenerationsReplaceRedeclaredHooksAndUnhookRemovedOnesOnce() {
        val first = batch()
        first.captureOldHooks(emptyList())
        first.install(
            Runnable {
                declare(first, "a")
                declare(first, "b")
            },
        )
        val firstResult = first.finishHotReload()
        assertEquals(2, firstResult.explicitHookCount)
        assertEquals(0, firstResult.removedOldHookCount)
        val a1 = live.getValue("a")
        val b1 = live.getValue("b")

        // "a" 被重新声明，framework 替换后旧 handle 已失效：任何再次访问都是真 bug。
        val second = batch()
        second.captureOldHooks(
            listOf(
                handle("a") { fail("replaced old handle must not be touched") },
                b1,
            ),
        )
        second.install(
            Runnable {
                declare(second, "a")
                declare(second, "c")
            },
        )
        val secondResult = second.finishHotReload()
        assertEquals(2, secondResult.explicitHookCount)
        assertEquals(1, secondResult.atomicallyReplacedHookCount)
        assertEquals(1, secondResult.removedOldHookCount)
        assertEquals(listOf("b"), unhooked)
        assertNotSame(a1, live.getValue("a"))

        // 第三代只保留 "a"：上一代留下的 "c" 恰好 unhook 一次，另一代的 "b" 不重复处理。
        val third = batch()
        third.captureOldHooks(listOf(live.getValue("a"), live.getValue("c")))
        third.install(Runnable { declare(third, "a") })
        val thirdResult = third.finishHotReload()
        assertEquals(1, thirdResult.explicitHookCount)
        assertEquals(1, thirdResult.atomicallyReplacedHookCount)
        assertEquals(1, thirdResult.removedOldHookCount)
        assertEquals(listOf("b", "c"), unhooked)
        assertEquals(setOf("a"), live.keys)
    }

    private fun batch(): HookReloadBatch = HookReloadBatch("chain", xposed)

    private fun handle(
        id: String,
        unhook: () -> Unit = {},
    ): XposedInterface.HookHandle =
        fake { method, _ ->
            when (method.name) {
                "getExecutable" -> {
                    executable
                }

                "getId" -> {
                    id
                }

                "unhook" -> {
                    unhook()
                    unhooked += id
                    live.remove(id)
                    null
                }

                else -> {
                    error("Unexpected HookHandle call: ${method.name}")
                }
            }
        }

    private fun declare(
        batch: HookReloadBatch,
        id: String,
    ) {
        batch.installHook(executable, 50, XposedInterface.ExceptionMode.DEFAULT, id, true, hooker) { _, _ ->
            handle(id).also { live[id] = it }
        }
    }
}

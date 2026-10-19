package io.github.lingqiqi5211.ezhooktool.xposed

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.ref.WeakReference

/**
 * 资源恢复失败发生在 hook 发布边界之前：新 hook 不会被安装，swap 会被撤掉，
 * 本次 attempt 的 ThreadLocal 也会释放，不留下“already in progress”/“nested”式的半状态。
 *
 * 资源逐个注入的部分成功（前一个换成新 loader、后一个失败）依赖真实 Android `Resources`/`ResourcesLoader`，
 * JVM fake 无法证明，需要真机验证。本测试复用本代初始化结果，不覆盖新一代模块初始化及整代重置。
 */
class HotReloadResourceRestoreFailureTest {
    private val fields =
        listOf(
            "base",
            "targetSnapshot",
            "automaticHookBatch",
            "automaticHookIds",
            "hotReloadFailure",
            "moduleResourcesInitialized",
            "currentHotReloadParam",
            "modulePath",
        )
    private lateinit var original: Map<String, Any?>
    private lateinit var compatSnapshot: XposedApiCompatSnapshot
    private val originalLoader = EzReflect.classLoader
    private val wasInitialized = EzReflect.isInitialized

    private fun field(name: String) = EzXposed::class.java.getDeclaredField(name).apply { isAccessible = true }

    @BeforeEach
    fun prepareModuleEntry() {
        compatSnapshot = XposedApiCompatSnapshot()
        original = fields.associateWith { field(it).get(null) }
        field("hotReloadFailure").set(null, null)
        field("moduleResourcesInitialized").setBoolean(null, true)
        field("modulePath").set(null, null)
        (field("targetReadyDispatcher").get(null) as TargetReadyDispatcher).reset()
    }

    @AfterEach
    fun restoreModuleEntry() {
        if (::compatSnapshot.isInitialized) compatSnapshot.close()
        EzResources.commitHotReload()
        if (::original.isInitialized) original.forEach { (name, value) -> field(name).set(null, value) }
        (field("targetReadyDispatcher").get(null) as TargetReadyDispatcher).reset()
        if (wasInitialized) EzReflect.init(originalLoader) else EzReflect.reset()
    }

    @Test
    fun resourceRestoreFailurePublishesNoHookAndDisarmsTheSwap() {
        val info =
            ApplicationInfo().apply {
                packageName = "test"
                sourceDir = "/missing-module.apk"
            }
        val base =
            fakeXposed { method, _ ->
                if (method.name == "getModuleApplicationInfo") info else error("Unexpected Xposed call: ${method.name}")
            }
        EzXposed.base = base
        XposedApiCompat.resolve(base)

        val snapshot = TargetSnapshot("test", "test", javaClass.classLoader!!, null, false)
        val resources = arrayOf<Any?>(listOf(Resources(null, null, null)), null)
        val saved = snapshot.toCrossGenArray(emptyArray(), resources)
        val param =
            fake<XposedModuleInterface.HotReloadedParam> { method, _ ->
                when (method.name) {
                    "getSavedInstanceState" -> saved
                    "getProcessName" -> "test"
                    "isSystemServer" -> false
                    else -> error("Unexpected HotReloadedParam call: ${method.name}")
                }
            }

        // 复用同一个 param 与本代初始化结果，只验证恢复阶段的边界，不重复测 module 资源创建。
        field("currentHotReloadParam").set(null, WeakReference(param))

        val failure =
            assertThrows<IllegalStateException> {
                EzXposed.restoreHotReloaded(base, param, onOldHooks = null, onExtra = null)
            }
        assertTrue(
            causes(failure).any { it.message?.contains("Failed to restore module resources") == true },
            "resource failure must stay visible in the cause chain: ${causes(failure).map { it.message }}",
        )
        // 发布边界没有越过：target-ready 未开始，因而没有任何 hook 被安装或替换。
        assertEquals(TargetReadyState.NOT_STARTED, EzXposed.targetReadyState)
        assertSame(failure, field("hotReloadFailure").get(null))
        val activeAttempt = field("activeHotReloadAttempt").get(null) as ThreadLocal<*>
        assertNull(activeAttempt.get(), "failed restore must release the active attempt")

        // 恢复过程已经撤掉 swap：下一次尝试报的是资源错误，而不是 “already in progress”。
        val retry =
            assertThrows<IllegalStateException> {
                EzResources.restoreFromHotReload(arrayOf(listOf(Resources(null, null, null)), null))
            }
        assertTrue(retry.message!!.contains("Failed to restore module resources"), retry.message)
        EzResources.commitHotReload()

        // 失败记录挡下后续热重载，避免复用已失败的代次。
        val blocked =
            assertThrows<IllegalStateException> {
                EzXposed.restoreHotReloaded(base, param, onOldHooks = null, onExtra = null)
            }
        assertTrue(blocked.message!!.contains("restart the target process"), blocked.message)
        assertFalse(blocked.message!!.contains("nested"), blocked.message)
    }

    private fun causes(root: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = root
        while (current != null && current !in seen) {
            seen += current
            seen += current.suppressed
            current = current.cause
        }
        return seen
    }
}

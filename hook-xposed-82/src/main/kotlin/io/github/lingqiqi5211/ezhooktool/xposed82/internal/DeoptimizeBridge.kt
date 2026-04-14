package io.github.lingqiqi5211.ezhooktool.xposed82.internal

import de.robv.android.xposed.XposedBridge
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

internal object DeoptimizeBridge {
    private const val TAG = "Deoptimize"

    private val bridgeMethod: Method? = runCatching {
        XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", Member::class.java).also {
            it.isAccessible = true
        }
    }.getOrElse {
        EzReflect.logger.warn(TAG, "XposedBridge.deoptimizeMethod(Member) unavailable: ${it.message}")
        null
    }

    fun deoptimize(method: Method): Boolean = invoke(method)

    fun deoptimize(constructor: Constructor<*>): Boolean = invoke(constructor)

    fun deoptimizeMethods(clazz: Class<*>, vararg names: String) {
        val targetNames = names.toSet()
        for (method in clazz.declaredMethods) {
            if (method.name in targetNames) {
                invoke(method)
            }
        }
    }

    private fun invoke(member: Member): Boolean {
        val bridge = bridgeMethod ?: return false
        return runCatching {
            bridge.invoke(null, member)
            true
        }.getOrElse {
            EzReflect.logger.error(TAG, "deoptimize failed for $member", it)
            false
        }
    }
}

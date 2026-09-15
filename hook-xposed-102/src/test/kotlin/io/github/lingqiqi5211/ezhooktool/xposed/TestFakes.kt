package io.github.lingqiqi5211.ezhooktool.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal inline fun <reified T> fake(noinline call: (Method, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Fake${T::class.java.simpleName}"
            else -> call(method, args ?: emptyArray())
        }
    } as T

internal fun fakeXposed(
    call: (Method, Array<out Any?>) -> Any? = { method, _ ->
        error("Unexpected Xposed call: ${method.name}")
    },
): XposedInterface =
    fake { method, args ->
        if (method.name == "getApiVersion") 102 else call(method, args)
    }

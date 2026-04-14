package io.github.lingqiqi5211.ezhooktool.xposed82.internal

import java.util.Collections
import java.util.WeakHashMap

internal object AdditionalFields {
    private val instanceFields = Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, Any?>>())
    private val staticFields = Collections.synchronizedMap(WeakHashMap<Class<*>, MutableMap<String, Any?>>())

    fun setInstance(target: Any, key: String, value: Any?): Any? {
        val map = instanceFields.getOrPut(target) { mutableMapOf() }
        return map.put(key, value)
    }

    fun getInstance(target: Any, key: String): Any? = instanceFields[target]?.get(key)

    fun removeInstance(target: Any, key: String): Any? = instanceFields[target]?.remove(key)

    fun setStatic(target: Class<*>, key: String, value: Any?): Any? {
        val map = staticFields.getOrPut(target) { mutableMapOf() }
        return map.put(key, value)
    }

    fun getStatic(target: Class<*>, key: String): Any? = staticFields[target]?.get(key)

    fun removeStatic(target: Class<*>, key: String): Any? = staticFields[target]?.remove(key)
}

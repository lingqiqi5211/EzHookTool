package io.github.lingqiqi5211.ezhooktool.xposed.internal

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** 附加值保持强引用；值若反向持有目标，调用者必须显式移除，弱键不能打断该引用链。 */
internal object AdditionalFields {
    private val queue = ReferenceQueue<Any>()
    private val instanceFields = HashMap<IdentityKey, MutableMap<String, Any?>>()
    private val staticFields = Collections.synchronizedMap(WeakHashMap<Class<*>, MutableMap<String, Any?>>())

    private class IdentityKey(
        target: Any,
        queue: ReferenceQueue<Any>? = null,
    ) : WeakReference<Any>(target, queue) {
        private val hash = System.identityHashCode(target)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = this === other || other is IdentityKey && get()?.let { it === other.get() } == true
    }

    private fun cleanInstances() {
        while (true) instanceFields.remove(queue.poll() ?: return)
    }

    fun setInstance(
        target: Any,
        key: String,
        value: Any?,
    ): Any? =
        synchronized(instanceFields) {
            cleanInstances()
            val lookup = IdentityKey(target)
            val fields =
                instanceFields[lookup] ?: HashMap<String, Any?>().also {
                    instanceFields[IdentityKey(target, queue)] = it
                }
            fields.put(key, value)
        }

    fun getInstance(
        target: Any,
        key: String,
    ): Any? =
        synchronized(instanceFields) {
            cleanInstances()
            instanceFields[IdentityKey(target)]?.get(key)
        }

    fun removeInstance(
        target: Any,
        key: String,
    ): Any? =
        synchronized(instanceFields) {
            cleanInstances()
            val lookup = IdentityKey(target)
            val fields = instanceFields[lookup] ?: return@synchronized null
            val removed = fields.remove(key)
            if (fields.isEmpty()) instanceFields.remove(lookup)
            removed
        }

    private fun innerStatic(target: Class<*>): MutableMap<String, Any?> =
        synchronized(staticFields) {
            staticFields.getOrPut(target) { Collections.synchronizedMap(HashMap()) }
        }

    fun setStatic(
        target: Class<*>,
        key: String,
        value: Any?,
    ): Any? = innerStatic(target).put(key, value)

    fun getStatic(
        target: Class<*>,
        key: String,
    ): Any? {
        val map = synchronized(staticFields) { staticFields[target] } ?: return null
        return map[key]
    }

    fun removeStatic(
        target: Class<*>,
        key: String,
    ): Any? {
        val map = synchronized(staticFields) { staticFields[target] } ?: return null
        return map.remove(key)
    }
}

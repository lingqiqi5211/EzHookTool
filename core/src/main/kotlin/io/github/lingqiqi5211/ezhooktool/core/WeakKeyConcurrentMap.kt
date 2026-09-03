package io.github.lingqiqi5211.ezhooktool.core

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 弱引用键、按引用相等的并发映射。读路径无锁：一次 [ConcurrentHashMap] 查找，不持有键。
 * 死键在 [getOrPut] 和 [clear] 时顺带清理。
 */
internal class WeakKeyConcurrentMap<K : Any, V : Any> {
    private val map = ConcurrentHashMap<Any, V>()
    private val queue = ReferenceQueue<K>()

    fun get(key: K): V? = map[Lookup(key)]

    fun getOrPut(key: K, create: () -> V): V {
        map[Lookup(key)]?.let { return it }
        expunge()
        val created = create()
        return map.putIfAbsent(WeakKey(key, queue), created) ?: created
    }

    fun clear() {
        map.clear()
        expunge()
    }

    private fun expunge() {
        while (true) {
            val dead = queue.poll() ?: return
            map.remove(dead)
        }
    }

    private class WeakKey<K>(key: K, queue: ReferenceQueue<K>) : WeakReference<K>(key, queue) {
        private val hash = System.identityHashCode(key)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val mine = get() ?: return false
            return when (other) {
                is WeakKey<*> -> mine === other.get()
                is Lookup -> mine === other.key
                else -> false
            }
        }
    }

    private class Lookup(val key: Any) {
        private val hash = System.identityHashCode(key)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = when (other) {
            is WeakKey<*> -> other.get() === key
            is Lookup -> other.key === key
            else -> false
        }
    }
}

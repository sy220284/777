package com.labteto.dshmobile.harness.registry

/** Shared publication lock: a lifecycle commit publishes all registry surfaces together. */
internal class RegistryEntries<T : Any>(initial: Map<String, T> = emptyMap()) {
    private val entries = LinkedHashMap(initial)
    private var target: RegistryEntries<T>? = null

    private fun current(): RegistryEntries<T> = target?.current() ?: this

    fun register(id: String, value: T, replace: Boolean) = atomic {
        require(id.isNotBlank()) { "注册项编号不能为空" }
        val map = current().entries
        if (!replace) require(id !in map) { "注册项已存在：$id" }
        map[id] = value
    }
    fun remove(id: String): T? = atomic { current().entries.remove(id) }
    fun get(id: String): T? = atomic { current().entries[id] }
    fun snapshot(): Map<String, T> = atomic { LinkedHashMap(current().entries) }
    fun restore(snapshot: Map<String, T>) = atomic {
        current().entries.apply { clear(); putAll(snapshot) }
    }
    fun fork(): RegistryEntries<T> = RegistryEntries(snapshot())

    /** Retained installation contexts follow the live registry after successful publication. */
    fun publishTo(destination: RegistryEntries<T>) = atomic {
        destination.restore(snapshot())
        target = destination.current()
        entries.clear()
    }

    companion object {
        private val publicationLock = Any()
        fun <R> atomic(block: () -> R): R = synchronized(publicationLock, block)
    }
}

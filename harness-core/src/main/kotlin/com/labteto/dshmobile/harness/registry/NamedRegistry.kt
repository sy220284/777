package com.labteto.dshmobile.harness.registry

/** Thread-safe named registry with isolated lifecycle staging. */
class NamedRegistry<T : Any> private constructor(private val entries: RegistryEntries<T>) {
    constructor() : this(RegistryEntries())
    fun register(id: String, value: T, replace: Boolean = false) = entries.register(id, value, replace)
    fun unregister(id: String): T? = entries.remove(id)
    fun get(id: String): T? = entries.get(id)
    fun require(id: String): T = get(id) ?: error("注册项不存在：$id")
    fun ids(): List<String> = snapshot().keys.toList()
    fun values(): List<T> = snapshot().values.toList()
    fun clear() = entries.restore(emptyMap())
    internal fun snapshot(): Map<String, T> = entries.snapshot()
    internal fun restore(snapshot: Map<String, T>) = entries.restore(snapshot)
    internal fun fork(): NamedRegistry<T> = NamedRegistry(entries.fork())
    internal fun publishTo(destination: NamedRegistry<T>) = entries.publishTo(destination.entries)
}

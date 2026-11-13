package com.labteto.dshmobile.harness.capability

import com.labteto.dshmobile.harness.registry.RegistryEntries
import kotlin.reflect.KClass

data class CapabilityDescriptor(
    val id: String,
    val version: Int = 1,
    val attributes: Map<String, String> = emptyMap(),
)

class CapabilityRegistry private constructor(
    private val entries: RegistryEntries<Pair<CapabilityDescriptor, Any>>,
) {
    constructor() : this(RegistryEntries())
    fun <T : Any> register(descriptor: CapabilityDescriptor, value: T, replace: Boolean = false) =
        entries.register(descriptor.id, descriptor to value, replace)
    fun unregister(id: String): Any? = entries.remove(id)?.second
    fun descriptor(id: String): CapabilityDescriptor? = entries.get(id)?.first
    fun descriptors(): List<CapabilityDescriptor> = entries.snapshot().values.map { it.first }
    fun <T : Any> get(id: String, type: KClass<T>): T? {
        val value = entries.get(id)?.second ?: return null
        return if (type.isInstance(value)) type.java.cast(value) else null
    }
    fun <T : Any> require(id: String, type: KClass<T>): T =
        get(id, type) ?: error("能力不存在或类型不匹配：$id")
    internal fun snapshot(): Map<String, Pair<CapabilityDescriptor, Any>> = entries.snapshot()
    internal fun restore(snapshot: Map<String, Pair<CapabilityDescriptor, Any>>) = entries.restore(snapshot)
    internal fun fork(): CapabilityRegistry = CapabilityRegistry(entries.fork())
    internal fun publishTo(destination: CapabilityRegistry) = entries.publishTo(destination.entries)
}

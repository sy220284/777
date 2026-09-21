package com.labteto.dshmobile.harness.capability

import kotlin.reflect.KClass

data class CapabilityDescriptor(
    val id: String,
    val version: Int = 1,
    val attributes: Map<String, String> = emptyMap(),
)

class CapabilityRegistry {
    private data class Entry(
        val descriptor: CapabilityDescriptor,
        val value: Any,
    )

    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun <T : Any> register(
        descriptor: CapabilityDescriptor,
        value: T,
        replace: Boolean = false,
    ) {
        require(descriptor.id.isNotBlank()) { "能力编号不能为空" }
        if (!replace) require(descriptor.id !in entries) { "能力已注册：${descriptor.id}" }
        entries[descriptor.id] = Entry(descriptor, value)
    }

    @Synchronized
    fun unregister(id: String): Any? = entries.remove(id)?.value

    @Synchronized
    fun descriptor(id: String): CapabilityDescriptor? = entries[id]?.descriptor

    @Synchronized
    fun descriptors(): List<CapabilityDescriptor> = entries.values.map(Entry::descriptor)

    @Synchronized
    fun <T : Any> get(id: String, type: KClass<T>): T? {
        val value = entries[id]?.value ?: return null
        return if (type.isInstance(value)) type.java.cast(value) else null
    }

    fun <T : Any> require(id: String, type: KClass<T>): T =
        get(id, type) ?: error("能力不存在或类型不匹配：$id")
}

package com.labteto.dshmobile.harness.registry

/** Thread-safe named registry used by plugins for models, commands, events, projections and settings. */
class NamedRegistry<T : Any> {
    private val entries = linkedMapOf<String, T>()

    @Synchronized
    fun register(id: String, value: T, replace: Boolean = false) {
        require(id.isNotBlank()) { "注册项编号不能为空" }
        if (!replace) require(id !in entries) { "注册项已存在：$id" }
        entries[id] = value
    }

    @Synchronized
    fun unregister(id: String): T? = entries.remove(id)

    @Synchronized
    fun get(id: String): T? = entries[id]

    @Synchronized
    fun require(id: String): T = get(id) ?: error("注册项不存在：$id")

    @Synchronized
    fun ids(): List<String> = entries.keys.toList()

    @Synchronized
    fun values(): List<T> = entries.values.toList()

    @Synchronized
    fun clear() = entries.clear()
}

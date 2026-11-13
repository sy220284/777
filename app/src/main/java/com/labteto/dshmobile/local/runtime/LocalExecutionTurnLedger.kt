package com.labteto.dshmobile.local.runtime

/**
 * Main-thread-owned foreground lease identities. A completed turn may only release its own key.
 * Work steps update the same entry instead of starting another Android foreground service.
 */
internal class LocalExecutionTurnLedger {
    data class Hold(val key: String, val sessionId: String, val label: String, val step: Int, val needsCpu: Boolean = true)

    private val entries = linkedMapOf<String, Hold>()

    fun acquire(hold: Hold) {
        require(hold.key.isNotBlank() && hold.sessionId.isNotBlank())
        check(!entries.containsKey(hold.key)) { "执行租约重复申请：${hold.key}" }
        entries[hold.key] = hold
    }

    fun update(key: String, step: Int): Boolean {
        val current = entries[key] ?: return false
        entries[key] = current.copy(step = step)
        return true
    }

    fun setCpuActive(key: String, active: Boolean): Boolean {
        val current = entries[key] ?: return false
        entries[key] = current.copy(needsCpu = active)
        return true
    }

    fun needsCpu(): Boolean = entries.values.any { it.needsCpu }
    fun release(key: String): Hold? = entries.remove(key)
    fun contains(key: String): Boolean = entries.containsKey(key)
    fun isEmpty(): Boolean = entries.isEmpty()
    fun latest(): Hold? = entries.values.lastOrNull()
    fun size(): Int = entries.size
}

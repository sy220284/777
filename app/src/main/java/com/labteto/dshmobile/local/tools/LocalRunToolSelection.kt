package com.labteto.dshmobile.local.tools

/**
 * One run's model-visible optional-tool selection. The selected names remain the only
 * capability fact; exactFocus only records how that same set was chosen.
 *
 * The live ToolRegistry and per-call permissions remain authoritative for execution.
 */
internal class LocalRunToolSelection : LinkedHashSet<String>() {
    @Volatile
    var exactFocus: Boolean = false
        private set

    /** Explicit select: wins over automatic pins and task hints until the task changes. */
    fun markExactFocus() { exactFocus = true }

    /** The task cursor and its model-visible selection share this run's ownership. */
    @Volatile private var lastTask: String? = null

    @Synchronized fun isNewTask(task: String): Boolean {
        if (task == lastTask) return false
        lastTask = task
        return true
    }

    @Synchronized fun resetTask() { lastTask = null }

    /** A new real user task may use normal relevance/pinned-tool admission again. */
    fun beginNewTask() { exactFocus = false }

    override fun clear() {
        super.clear()
        exactFocus = false
    }
}

package com.labteto.dshmobile.local.work

/** Per-run ranking cursor: explicit discovery wins until the user supplies a changed task. */
internal class LocalWorkToolPriority {
    private var lastTask: String? = null
    fun isNewTask(task: String): Boolean {
        if (task == lastTask) return false
        lastTask = task
        return true
    }
    fun reset() { lastTask = null }
}

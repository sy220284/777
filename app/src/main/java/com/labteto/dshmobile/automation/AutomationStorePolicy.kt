package com.labteto.dshmobile.automation

internal object AutomationStorePolicy {
    const val MAX_TASKS = 256

    fun requireCapacity(tasks: List<AutomationTask>, taskId: String) {
        require(tasks.any { it.id == taskId } || tasks.size < MAX_TASKS) {
            "自动任务数量已达上限（$MAX_TASKS），请删除不再使用的任务后重试"
        }
    }
}

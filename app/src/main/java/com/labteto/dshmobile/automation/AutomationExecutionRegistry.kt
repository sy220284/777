package com.labteto.dshmobile.automation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AutomationTaskExecutionLease internal constructor(
    private val taskId: String,
    private val ownerId: String,
) : AutoCloseable {
    override fun close() {
        AutomationExecutionRegistry.release(taskId, ownerId)
    }
}

/**
 * Prevents scheduled and manual WorkManager requests for the same automation from executing at the
 * same time. Durable generation checks remain the authority for whether a completed run may commit.
 */
internal object AutomationExecutionRegistry {
    private val owners = ConcurrentHashMap<String, String>()

    fun tryAcquire(taskId: String): AutomationTaskExecutionLease? {
        require(taskId.isNotBlank()) { "任务编号不能为空" }
        val ownerId = UUID.randomUUID().toString()
        return if (owners.putIfAbsent(taskId, ownerId) == null) {
            AutomationTaskExecutionLease(taskId, ownerId)
        } else {
            null
        }
    }

    internal fun isRunning(taskId: String): Boolean = owners.containsKey(taskId)

    internal fun release(taskId: String, ownerId: String) {
        owners.remove(taskId, ownerId)
    }
}

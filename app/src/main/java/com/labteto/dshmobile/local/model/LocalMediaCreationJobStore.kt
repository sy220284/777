package com.labteto.dshmobile.local.model

import kotlinx.serialization.Serializable

/**
 * Persistent submission receipts for billable synchronous media endpoints.
 * On process death an in-flight HTTP request is UNKNOWN, never silently retried.
 * Completed receipts share their identifier with the actual media result.
 */
@Serializable
enum class LocalMediaCreationPhase { PREPARING, SUBMITTING, SAVING, OUTCOME_UNKNOWN, FAILED, SAVE_FAILED, SUCCEEDED }

@Serializable
data class LocalMediaCreationJob(
    val id: String,
    val sessionId: String,
    val kind: String,
    val provider: String,
    val model: String,
    val prompt: String?,
    val createdAtMillis: Long,
    val phase: LocalMediaCreationPhase = LocalMediaCreationPhase.SUBMITTING,
    val error: String? = null,
    val profileId: String? = null,
    val routeFingerprint: String? = null,
    val errorCode: String? = null,
)

@Serializable
internal data class LocalMediaCreationJobList(
    val jobs: List<LocalMediaCreationJob> = emptyList(),
)

internal object LocalMediaCreationJobRecovery {
    fun recover(items: List<LocalMediaCreationJob>): List<LocalMediaCreationJob> =
        items.map { item ->
            if (item.phase == LocalMediaCreationPhase.PREPARING)
                item.copy(phase = LocalMediaCreationPhase.FAILED, errorCode = "NOT_SENT", error = "请求尚未发送，准备过程已中断，可重新提交。")
            else if (item.phase == LocalMediaCreationPhase.SAVING)
                item.copy(phase = LocalMediaCreationPhase.SAVE_FAILED, errorCode = "LOCAL_SAVE_INTERRUPTED", error = "服务商已返回成果，本地保存中断；请核对已有文件或服务商记录，不要重复生成。")
            else if (item.phase == LocalMediaCreationPhase.SUBMITTING)
                item.copy(phase = LocalMediaCreationPhase.OUTCOME_UNKNOWN,
                    error = "应用退出时生成结果未确认；请先核对服务商记录，避免重复扣费。")
            else item
        }
}

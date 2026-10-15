package com.labteto.dshmobile.local.model

import kotlinx.serialization.Serializable

/**
 * This is the single durable fact source for provider-side video creation.
 * SUBMITTING means the external request may already have been accepted. If the process dies
 * before its returned ID is committed, never silently replay a possibly billable request.
 */
@Serializable
enum class LocalMediaDeliveryPhase { PENDING, SAVING, SAVED, FAILED }

@Serializable
enum class LocalMediaVideoPhase { SUBMITTING, OUTCOME_UNKNOWN, QUEUED, PROCESSING, SUCCEEDED, FAILED }

@Serializable
data class LocalMediaVideoTask(
    val id: String,
    val offerId: String,
    val routeFingerprint: String,
    val provider: String,
    val model: String,
    val profileId: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val phase: LocalMediaVideoPhase,
    val providerTaskId: String? = null,
    val downloadUrl: String? = null,
    val lastError: String? = null,
    val sessionId: String? = null,
    val description: String? = null,
    val fileName: String? = null,
    val deliveryPhase: LocalMediaDeliveryPhase = LocalMediaDeliveryPhase.PENDING,
    val sourceMessageId: String? = null,
    val revisionOf: String? = null,
) {
    /** A removed, replaced or re-keyed account must not be used to poll an old task. */
    fun belongsTo(offer: LocalMediaOffer): Boolean =
        offerId == offer.id && routeFingerprint == offer.profile.routeFingerprint() &&
            provider == offer.provider.name && model == offer.model
}

@Serializable
internal data class LocalMediaVideoTaskList(val tasks: List<LocalMediaVideoTask> = emptyList())

/** Pure state transitions shared by persistence and JVM crash-recovery regression tests. */
internal object LocalMediaVideoTransitions {
    fun recover(tasks: List<LocalMediaVideoTask>): List<LocalMediaVideoTask> =
        tasks.map { task ->
            if (task.phase == LocalMediaVideoPhase.SUBMITTING) task.copy(
                phase = LocalMediaVideoPhase.OUTCOME_UNKNOWN,
                lastError = "应用退出时视频提交结果尚未确认。请检查服务商后台，勿直接重复生成。",
            ) else task
        }

    fun accepted(
        previous: LocalMediaVideoTask,
        providerTaskId: String,
        now: Long,
    ): LocalMediaVideoTask {
        require(providerTaskId.isNotBlank() && providerTaskId.length <= 256)
        check(previous.phase == LocalMediaVideoPhase.SUBMITTING)
        return previous.copy(
            phase = LocalMediaVideoPhase.QUEUED,
            providerTaskId = providerTaskId,
            updatedAtMillis = now,
            lastError = null,
        )
    }

    fun polled(
        previous: LocalMediaVideoTask,
        phase: LocalMediaVideoPhase,
        url: String?,
        now: Long,
    ): LocalMediaVideoTask {
        require(phase in setOf(
            LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING,
            LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED,
        ))
        require(!previous.providerTaskId.isNullOrBlank())
        if (previous.phase in setOf(LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED)) {
            return previous
        }
        if (phase == LocalMediaVideoPhase.SUCCEEDED) {
            require(url != null && url.startsWith("https://")) { "生成已完成但缺少可靠视频地址" }
        }
        return previous.copy(
            phase = phase,
            downloadUrl = if (phase == LocalMediaVideoPhase.SUCCEEDED) url else null,
            lastError = null,
            updatedAtMillis = now,
        )
    }
}

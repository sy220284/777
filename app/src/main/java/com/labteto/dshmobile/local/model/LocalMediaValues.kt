package com.labteto.dshmobile.local.model

import kotlinx.serialization.Serializable

/** Immutable Media API values; provider transitions, account checks and persistence stay in Media. */
enum class LocalMediaKind { IMAGE, VIDEO, SPEECH, TRANSCRIBE }
enum class LocalMediaProvider { MINIMAX, OPENAI, QWEN, GEMINI }

data class LocalMediaOffer(
    val profile: LocalModelProfile,
    val provider: LocalMediaProvider,
    val kind: LocalMediaKind,
    val model: String,
    val label: String,
) {
    val id: String get() = "${profile.id}:${provider.name}:${kind.name}:$model"
}


@Serializable
data class LocalMediaResult(
    val id: String,
    val model: String,
    val provider: String,
    val kind: String,
    val createdAtMillis: Long,
    val fileName: String? = null,
    val remoteUrl: String? = null,
    /** Null for legacy/unassigned results, which stay in the global media archive. */
    val sessionId: String? = null,
    val description: String? = null,
    val textOutput: String? = null,
    val sourceMessageId: String? = null,
    val profileId: String? = null,
    val routeFingerprint: String? = null,
    val imageRatio: String? = null,
    val voiceId: String? = null,
    val revisionOf: String? = null,
    val credentialSignature: String? = null,
) {
    init { require(listOf(fileName, remoteUrl, textOutput).count { it != null } == 1) }
}


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
    val credentialSignature: String? = null,
)


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
    val sourceMessageId: String? = null,
    val revisionOf: String? = null,
    val credentialSignature: String? = null,
)


internal data class LocalMediaCreationSnapshot(
    val job: LocalMediaCreationJob?,
    val result: LocalMediaResult?,
)

data class LocalMediaPage(
    val results: List<LocalMediaResult> = emptyList(),
    val jobs: List<LocalMediaCreationJob> = emptyList(),
    val videos: List<LocalMediaVideoTask> = emptyList(),
    val hasMore: Boolean = false,
    val unavailable: Boolean = false,
)


data class LocalMediaVoice(val id: String, val name: String)

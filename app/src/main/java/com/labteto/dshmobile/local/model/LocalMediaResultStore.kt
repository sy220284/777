package com.labteto.dshmobile.local.model

import kotlinx.serialization.Serializable

/** Finished media results, separate from provider-side video job lifecycle. No prompt or secret. */
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
    /** Originating chat message, not a fabricated assistant turn. */
    val sourceMessageId: String? = null,
    /** Original frozen creator identity and parameters; old results may have no such evidence. */
    val profileId: String? = null,
    val routeFingerprint: String? = null,
    val imageRatio: String? = null,
    val voiceId: String? = null,
    val revisionOf: String? = null,
) {
    init { require(listOf(fileName, remoteUrl, textOutput).count { it != null } == 1) }
}

@Serializable
internal data class LocalMediaResultList(val results: List<LocalMediaResult> = emptyList())

/**
 * Media results are app-private durable files, never disposable network cache.
 * Provider CDN URLs are indexed as *temporary links*, not falsely described as downloaded.
 * Explicit user removal is required; we don't evict paid creations automatically.
 */

package com.labteto.dshmobile.local.presentation

/** Bounded read-only reference into existing Tool/Session facts; no duplicate file ownership. */
data class LocalArtifactUiItem(
    val reference: String,
    val category: String,
    val sourceCallId: String?,
    val asOfSequence: Long,
    val currentlyAvailable: Boolean? = null,
    val versionAtCreation: String? = null,
    val versionNow: String? = null,
)

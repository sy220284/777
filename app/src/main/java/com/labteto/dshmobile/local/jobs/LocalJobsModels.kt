package com.labteto.dshmobile.local.jobs



/** User-visible state for a background command. */
data class LocalJobInfo(
    val id: String,
    val label: String,
    val status: String,
    val ownerSessionId: String? = null,
)

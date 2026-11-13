package com.labteto.dshmobile.local.model



/** Model-owned runtime configuration and selected route identity. */
data class LocalModelState(
    val configured: Boolean = false,
    val model: String = LocalModelConfigContract.DEFAULT_MODEL,
    val baseUrl: String = LocalModelConfigContract.DEFAULT_BASE_URL,
    val modelSelection: LocalModelSelectionState = LocalModelSelectionState(),
    val modelAttempts: Int = LocalModelConfigContract.DEFAULT_MODEL_ATTEMPTS,
    val imageInputMode: LocalImageInputMode = LocalImageInputMode.AUTO,
)

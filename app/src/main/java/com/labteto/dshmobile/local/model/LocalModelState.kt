package com.labteto.dshmobile.local.model



/** Model-owned runtime configuration and selected route identity. */
data class LocalModelState(
    val configured: Boolean = false,
    val model: String = "deepseek-flash",
    val baseUrl: String = "https://api.deepseek.com",
    val modelSelection: LocalModelSelectionState = LocalModelSelectionState(),
    val modelAttempts: Int = 3,
    val imageInputMode: LocalImageInputMode = LocalImageInputMode.AUTO,
)

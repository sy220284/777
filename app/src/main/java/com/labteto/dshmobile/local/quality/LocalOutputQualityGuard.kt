package com.labteto.dshmobile.local.quality

/** A domain-owned delivery assessment; Work verifies concrete Goal and artifact evidence. */
internal data class LocalOutputQualityResult(
    val text: String,
    val findings: List<String> = emptyList(),
    val changed: Boolean = false,
)

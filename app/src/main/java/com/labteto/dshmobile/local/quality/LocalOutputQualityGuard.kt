package com.labteto.dshmobile.local.quality

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode

internal data class LocalOutputQualityContext(
    val usageMode: LocalUsageMode? = null,
    val state: LocalHarnessState? = null,
)

internal data class LocalOutputQualityResult(
    val text: String,
    val findings: List<String> = emptyList(),
    val changed: Boolean = false,
)

internal fun interface LocalOutputQualityGuard {
    fun inspect(
        text: String,
        context: LocalOutputQualityContext,
    ): LocalOutputQualityResult
}

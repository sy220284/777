package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import com.labteto.dshmobile.local.model.LocalModelTemperatureRange

internal data class LocalConversationTemperatureUi(
    val range: LocalModelTemperatureRange? = null,
    val tuningAvailable: Boolean = false,
)

/** Resolve optional account metadata outside Compose's inline expression tracking. */
internal fun localConversationTemperatureUi(profile: LocalModelProfile?, fast: Boolean): LocalConversationTemperatureUi {
    val selected = profile ?: return LocalConversationTemperatureUi()
    val contract = LocalEffectiveModelContracts.forProfile(selected)
    val range = contract.temperature
    return LocalConversationTemperatureUi(range,
        contract.runtime.temperature && range != null && (!range.requiresDisabledThinking || fast))
}

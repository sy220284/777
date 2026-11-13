package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState

/** Split settings ownership by credential source without changing the runtime's unified model picker. */
internal fun List<LocalModelProfile>.apiModelSettingsProfiles(): List<LocalModelProfile> =
    filter { it.authKind == LocalModelAuthKind.API_KEY }

internal data class ChatGptAccountModelRow(
    val slug: String,
    val displayName: String,
    val profileId: String?,
    val active: Boolean,
)

/** Only the selected account's models are shown; stale profiles remain visible but cannot be selected. */
internal fun chatGptAccountModelRows(
    state: ChatGptUiState,
    selection: LocalModelSelectionState,
): List<ChatGptAccountModelRow> {
    val selected = state.selectedAccount ?: return emptyList()
    if (!selected.signedIn || !selected.sharingEnabled) return emptyList()

    val accountProfiles = selection.profiles.filter {
        it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == selected.id
    }
    val profileBySlug = accountProfiles.associateBy(LocalModelProfile::model)
    val catalog = if (state.connected) {
        state.models
    } else {
        accountProfiles.map { ChatGptModelOption(it.model, it.displayName ?: it.model) }
    }
    return catalog.distinctBy(ChatGptModelOption::slug).map { option ->
        val profile = profileBySlug[option.slug]
        ChatGptAccountModelRow(
            slug = option.slug,
            displayName = option.displayName.ifBlank { option.slug },
            profileId = profile?.id,
            active = profile != null && selection.isActive(profile),
        )
    }
}

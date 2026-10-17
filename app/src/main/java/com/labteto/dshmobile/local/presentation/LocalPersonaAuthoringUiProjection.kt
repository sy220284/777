package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.authoringDraft
import com.labteto.dshmobile.local.chat.applyTo
import com.labteto.dshmobile.uicontract.UiPersonaDraft

/** UI-facing conversion bridge. Chat remains the single authority for facts and canonicalization. */
internal fun PersonaProfile.toUiAuthoringDraft(): UiPersonaDraft = authoringDraft()
internal fun UiPersonaDraft.toDomainPersona(previous: PersonaProfile): PersonaProfile = applyTo(previous)

package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import kotlinx.serialization.json.Json

/** V6 form restoration only. It cannot save a profile or choose a different session target. */
internal class LocalPersonaEditorState(
    draftState: MutableState<String?>,
    identityState: MutableState<String?>,
    tuningState: MutableState<String?>,
) {
    private var draftJson by draftState
    private var identityJson by identityState
    private var tuningJson by tuningState
    val profile by derivedStateOf { draftJson?.let {
        runCatching { Json.decodeFromString(PersonaProfile.serializer(), it) }.getOrNull()
    } }
    val identity by derivedStateOf { decodeIdentity(identityJson) }
    val tuningIdentity by derivedStateOf { decodeIdentity(tuningJson) }

    fun open(profile: PersonaProfile, identity: UiSessionIdentity?) {
        draftJson = Json.encodeToString(PersonaProfile.serializer(), profile)
        identityJson = encodeIdentity(identity)
    }
    fun captureTuning(identity: UiSessionIdentity?) { tuningJson = encodeIdentity(identity) }
    fun clear() { draftJson = null; identityJson = null }

    private fun encodeIdentity(identity: UiSessionIdentity?) = identity?.let {
        Json.encodeToString(UiSessionIdentity.serializer(), it)
    }
    private fun decodeIdentity(value: String?) = value?.let {
        runCatching { Json.decodeFromString(UiSessionIdentity.serializer(), it) }.getOrNull()
    }
}

@Composable
internal fun rememberLocalPersonaEditorState(): LocalPersonaEditorState {
    val draft = rememberSaveable { mutableStateOf<String?>(null) }
    val identity = rememberSaveable { mutableStateOf<String?>(null) }
    val tuning = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(draft, identity, tuning) { LocalPersonaEditorState(draft, identity, tuning) }
}

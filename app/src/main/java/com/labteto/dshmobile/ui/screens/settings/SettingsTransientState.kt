package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.runtime.mutableStateOf
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** ViewModel memory only: API keys and reviewed reports never enter saved-state Bundles. */
internal class ApiModelEditorState {
    val showEditor = mutableStateOf(false)
    val model = mutableStateOf(LocalModelPresets.entries.first().model)
    val baseUrl = mutableStateOf(LocalModelPresets.entries.first().baseUrl)
    val protocol = mutableStateOf(LocalModelProtocol.CHAT_COMPLETIONS)
    val custom = mutableStateOf(false)
    val editingProfileId = mutableStateOf<String?>(null)
    val editorGeneration = mutableStateOf(0L)
    val apiKey = mutableStateOf("")
    val credentialSourceProfileId = mutableStateOf<String?>(null)
    val contextWindowTokens = mutableStateOf("")
    fun close() { apiKey.value = ""; showEditor.value = false; editorGeneration.value++ }
}

/** UI operation ownership follows the Settings ViewModel, never a restored busy flag. */
internal class SettingsStorageOperations(private val scope: CoroutineScope) {
    val busy = mutableStateOf(false)
    val status = mutableStateOf<LocalSessionStorageStatus?>(null)
    val notice = mutableStateOf<String?>(null)
    private var operation: Job? = null
    fun launch(block: suspend () -> Unit): Boolean {
        if (!scope.isActive || busy.value || operation?.isActive == true) return false
        busy.value = true
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { block() } finally { busy.value = false }
        }
        return true
    }
}

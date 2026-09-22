package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI adapter for the process-wide on-device Harness engine. */
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val engine: LocalHarnessEngine,
) : ViewModel() {
    val state = engine.state

    fun configure(apiKey: String, model: String, baseUrl: String) = engine.configure(apiKey, model, baseUrl)
    fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()) = engine.send(text, attachments)
    suspend fun importAttachment(uri: Uri): LocalImportedAttachment = engine.importAttachment(uri)
    suspend fun diagnoseNetwork(target: String): String = engine.diagnoseNetwork(target)
    fun environmentInfo(): String = engine.environmentInfoForUi()
    fun approve() = engine.answerApproval(true)
    fun deny() = engine.answerApproval(false)
    fun enableAutoApproval() = engine.enableAutoApproval()
    fun enableDeviceApprovalLease() = engine.enableDeviceApprovalLease()
    fun disableAutoApproval() = engine.disableAutoApproval()
    fun answerQuestion(answer: String) = engine.answerQuestion(answer)
    fun stop() = engine.stop()
    fun newSession() = engine.createSession(LocalConversationMode.INDEPENDENT)
    fun createSession(mode: LocalConversationMode) = engine.createSession(mode)
    fun setPlanMode(enabled: Boolean) = engine.setPlanMode(enabled)
    fun switchSession(sessionId: String) = engine.switchSession(sessionId)
    fun clearCredential() = engine.clearCredential()
}

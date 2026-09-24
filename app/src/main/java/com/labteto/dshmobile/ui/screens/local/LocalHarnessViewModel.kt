package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI adapter for the process-wide on-device Harness engine. */
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val engine: LocalHarnessEngine,
) : ViewModel() {
    val state = engine.state

    fun configure(apiKey: String, model: String, baseUrl: String) = engine.configure(apiKey, model, baseUrl)
    fun selectModel(model: String) = engine.selectModel(model)
    fun setImageInputMode(mode: LocalImageInputMode) = engine.configureImageInputMode(mode)
    fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()) = engine.send(text, attachments)
    suspend fun importAttachment(uri: Uri): LocalImportedAttachment = engine.importAttachment(uri)
    suspend fun workspaceFiles() = engine.workspaceFilesForUi()
    suspend fun conversationFiles(sessionId: String) = engine.conversationFilesForUi(sessionId)
    suspend fun previewWorkspaceFile(path: String) = engine.previewWorkspaceFileForUi(path)
    fun approve(callId: String) = engine.answerApproval(callId, true)
    fun deny(callId: String) = engine.answerApproval(callId, false)
    fun enableAutoApproval() = engine.enableAutoApproval()
    fun enableAutoApprovalForPending(callId: String) = engine.enableAutoApprovalForPending(callId)
    fun enableDeviceApprovalLease(callId: String) = engine.enableDeviceApprovalLease(callId)
    fun disableDeviceApprovalLease() = engine.disableDeviceApprovalLease()
    fun disableAutoApproval() = engine.disableAutoApproval()
    fun answerQuestion(callId: String, answer: String) = engine.answerQuestion(callId, answer)
    fun cancelQuestion(callId: String) = engine.cancelQuestion(callId)
    fun stop() = engine.stop()
    fun newSession() = engine.createSession(LocalConversationMode.INDEPENDENT)
    fun createSession(mode: LocalConversationMode) = engine.createSession(mode)
    fun setPlanMode(enabled: Boolean) = engine.setPlanMode(enabled)
    fun switchSession(sessionId: String) = engine.switchSession(sessionId)
    fun clearCredential() = engine.clearCredential()
}

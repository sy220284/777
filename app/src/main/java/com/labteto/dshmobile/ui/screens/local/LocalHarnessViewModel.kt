package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.PersonaProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI adapter for the process-wide on-device Harness engine. */
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val engine: LocalHarnessEngine,
    private val personaAutoFillService: PersonaAutoFillService,
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
    fun approve() = engine.answerApproval(true)
    fun deny() = engine.answerApproval(false)
    fun enableAutoApproval() = engine.enableAutoApproval()
    fun enableDeviceApprovalLease() = engine.enableDeviceApprovalLease()
    fun disableDeviceApprovalLease() = engine.disableDeviceApprovalLease()
    fun disableAutoApproval() = engine.disableAutoApproval()
    fun answerQuestion(answer: String) = engine.answerQuestion(answer)
    fun cancelQuestion() = engine.cancelQuestion()
    fun stop() = engine.stop()
    fun newSession() = engine.createSession(LocalConversationMode.INDEPENDENT)
    fun createSession(mode: LocalConversationMode) = engine.createSession(mode)
    fun setPlanMode(enabled: Boolean) = engine.setPlanMode(enabled)
    fun switchUsageMode(mode: LocalUsageMode) = engine.switchUsageMode(mode)
    fun configureChatPersona(profile: PersonaProfile) = engine.configureChatPersona(profile)

    suspend fun autoFillChatPersona(description: String): Result<PersonaProfile> {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) {
            return Result.failure(IllegalStateException("当前状态暂时不能生成人设"))
        }
        if (!snapshot.configured) {
            return Result.failure(IllegalStateException("请先完成模型配置"))
        }
        return runCatching {
            personaAutoFillService.generate(
                model = snapshot.model,
                baseUrl = snapshot.baseUrl,
                current = snapshot.chatPersona,
                recentMessages = snapshot.messages,
                description = description,
            ).also(engine::configureChatPersona)
        }
    }
    fun switchSession(sessionId: String) = engine.switchSession(sessionId)
    fun clearCredential() = engine.clearCredential()
}

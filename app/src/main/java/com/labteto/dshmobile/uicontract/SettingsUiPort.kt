package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.local.model.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelPreset
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.presentation.LocalSettingsMemoryContext
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** UI projection; provider eligibility is computed by the Model owner, not by the renderer. */
data class UiSettingsSnapshot(
    val loading: Boolean = true, val model: String = "deepseek-flash", val baseUrl: String = "https://api.deepseek.com",
    val modelSelection: LocalModelSelectionState = LocalModelSelectionState(),
    val mainMaxSteps: Int = 128, val subagentMaxSteps: Int = 128, val modelAttempts: Int = 3,
    val imageInputMode: LocalImageInputMode = LocalImageInputMode.AUTO,
    val userRules: String = "", val autoRecall: Boolean = true, val autoMemory: Boolean = true,
    val chatStyleGuardEnabled: Boolean = true, val chatStyleGuardCustomPhrases: List<String> = emptyList(),
    val styleGuardHits: List<String> = emptyList(), val personaName: String = "",
    val usage: DeepSeekUsageSnapshot = DeepSeekUsageSnapshot(), val error: String? = null,
    val miniMaxQuotaProfile: LocalModelProfile? = null,
    val personaBannedPhrases: List<String> = emptyList(),
) { val modelProfiles: List<LocalModelProfile> get() = modelSelection.profiles }

/** Workflow APIs retain their original owners. No credentials or mutable stores are returned. */
interface SettingsUiPort {
    val state: Flow<UiSettingsSnapshot>
    val initialState: UiSettingsSnapshot
    /** Model-owned public catalog; renderers never duplicate preset/model policy. */
    val apiModelPresets: List<LocalModelPreset>
    val chatGptState: StateFlow<ChatGptUiState>
    val chatStyleGuardBuiltInPhrases: List<String>
    val approvalMode: StateFlow<LocalApprovalMode>
    fun memoryContext(): LocalSettingsMemoryContext
    fun configureApprovalMode(mode: LocalApprovalMode)
    suspend fun refreshChatGpt()
    suspend fun connectChatGpt(existingAccountId: String? = null, requestPlanConsent: Boolean = false)
    suspend fun restartChatGptAuthorization(existingAccountId: String? = null)
    suspend fun cancelChatGptAuthorization()
    suspend fun testChatGptAccount(id: String): String
    suspend fun selectChatGptAccount(id: String)
    suspend fun disconnectChatGptAccount(id: String): String?
    suspend fun removeChatGptAccount(id: String): String?
    fun configureModel(apiKey: String, model: String, baseUrl: String)
    suspend fun saveModel(apiKey: String, model: String, baseUrl: String, protocol: LocalModelProtocol? = null,
        profileId: String? = null, contextWindowTokensOverride: Int? = null, credentialSourceProfileId: String? = null)
    suspend fun queryMiniMaxQuota(profile: LocalModelProfile): MiniMaxProviderUsage
    fun selectModel(id: String)
    fun removeModel(id: String)
    suspend fun testModel(apiKey: String, model: String, baseUrl: String, protocol: LocalModelProtocol? = null,
        profileId: String? = null, credentialSourceProfileId: String? = null): String
    suspend fun discoverApiModels(apiKey: String, baseUrl: String, protocol: LocalModelProtocol,
        credentialSourceProfileId: String? = null): List<String>
    fun configureImageInputMode(mode: LocalImageInputMode)
    fun configureRuntimeLimits(main: Int, subagent: Int, attempts: Int, workerProfileId: String?)
    /** Enqueue without claiming durability; callers that need confirmation use savePersonalization. */
    fun configurePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean)
    /** True only after the exact submitted generation is persisted and remains current. */
    suspend fun savePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean): Boolean
    fun configureChatStyleGuard(enabled: Boolean)
    fun addChatStyleGuardPhrase(value: String): Boolean
    fun removeChatStyleGuardPhrase(value: String)
    fun clearChatStyleGuardHits()
    suspend fun diagnoseNetwork(target: String): String
    suspend fun sessionStorageStatus(): LocalSessionStorageStatus
    suspend fun compactSessionStorage(): LocalSessionStorageStatus
    suspend fun exportSessionStorage(output: OutputStream): Long
    /** Validated missing sessions are restored at next app launch, never over live event logs. */
    suspend fun stageRestoreSessionStorage(archive: java.io.InputStream): Int
    suspend fun environmentInfo(): String
    suspend fun diagnosticReport(): String
    fun clearCredential()
}

sealed interface SettingsUiCommand {
    data class ApprovalMode(val mode: LocalApprovalMode) : SettingsUiCommand
    data class ImageInputMode(val mode: LocalImageInputMode) : SettingsUiCommand
    data class RuntimeLimits(val main: Int, val subagent: Int, val attempts: Int, val workerProfileId: String?) : SettingsUiCommand
    data class Personalization(val rules: String, val autoRecall: Boolean, val autoMemory: Boolean) : SettingsUiCommand
    data class StyleGuard(val enabled: Boolean) : SettingsUiCommand
    data class AddStylePhrase(val phrase: String) : SettingsUiCommand
    data class RemoveStylePhrase(val phrase: String) : SettingsUiCommand
    data object ClearStyleHits : SettingsUiCommand
    data class SelectModel(val id: String) : SettingsUiCommand
    data class RemoveModel(val id: String) : SettingsUiCommand
    data object ClearCredential : SettingsUiCommand
    class SaveModel(val apiKey: String, val model: String, val baseUrl: String,
        val protocol: LocalModelProtocol? = null, val profileId: String? = null,
        val contextWindowTokensOverride: Int? = null, val credentialSourceProfileId: String? = null) : SettingsUiCommand {
        override fun toString() = "SaveModel(<redacted>)"
    }
}
sealed interface SettingsUiReceipt {
    data object Applied : SettingsUiReceipt
    /** Model selection/removal is queued by the existing owner. Read state for its actual result. */
    data object Submitted : SettingsUiReceipt
    data object Rejected : SettingsUiReceipt
    data class Failed(val message: String?) : SettingsUiReceipt
}
fun SettingsUiPort.submit(command: SettingsUiCommand): SettingsUiReceipt = try {
    when (command) {
        is SettingsUiCommand.ApprovalMode -> configureApprovalMode(command.mode)
        is SettingsUiCommand.ImageInputMode -> configureImageInputMode(command.mode)
        is SettingsUiCommand.RuntimeLimits -> configureRuntimeLimits(command.main, command.subagent, command.attempts, command.workerProfileId)
        is SettingsUiCommand.Personalization -> {
            configurePersonalization(command.rules, command.autoRecall, command.autoMemory)
            return SettingsUiReceipt.Submitted
        }
        is SettingsUiCommand.StyleGuard -> configureChatStyleGuard(command.enabled)
        is SettingsUiCommand.AddStylePhrase -> if (!addChatStyleGuardPhrase(command.phrase)) return SettingsUiReceipt.Rejected
        is SettingsUiCommand.RemoveStylePhrase -> removeChatStyleGuardPhrase(command.phrase)
        SettingsUiCommand.ClearStyleHits -> clearChatStyleGuardHits()
        is SettingsUiCommand.SelectModel -> { selectModel(command.id); return SettingsUiReceipt.Submitted }
        is SettingsUiCommand.RemoveModel -> { removeModel(command.id); return SettingsUiReceipt.Submitted }
        SettingsUiCommand.ClearCredential -> { clearCredential(); return SettingsUiReceipt.Submitted }
        is SettingsUiCommand.SaveModel -> return SettingsUiReceipt.Rejected
    }
    SettingsUiReceipt.Applied
} catch (failure: Exception) { SettingsUiReceipt.Failed(failure.message) }

suspend fun SettingsUiPort.save(command: SettingsUiCommand.SaveModel): SettingsUiReceipt = try {
    saveModel(command.apiKey, command.model, command.baseUrl, command.protocol, command.profileId,
        command.contextWindowTokensOverride, command.credentialSourceProfileId)
    SettingsUiReceipt.Applied
} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (failure: Exception) { SettingsUiReceipt.Failed(failure.message) }

package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.ChatPersonaCorrectionNotice
import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalChatBranchState
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalGroupChatState
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalQuestion
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.PersonaProfile

/**
 * Shared conversation substrate rendered by Chat and Work.
 *
 * Each mode gets its own projection from the same runtime state. Fields owned by the other mode stay
 * at stable defaults, so Chat-only churn cannot wake Work and Work-only churn cannot wake Chat.
 */
data class LocalConversationSurfaceState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val sessionId: String = "",
    val usageMode: LocalUsageMode = LocalUsageMode.WORK,
    val running: Boolean = false,
    val messages: List<LocalHarnessMessage> = emptyList(),
    val workspacePath: String = "",
    val imageInputMode: LocalImageInputMode = LocalImageInputMode.AUTO,
    val error: String? = null,

    val galleryId: String? = null,
    val galleryStoryId: String? = null,
    val chatPersona: PersonaProfile = PersonaProfile(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val replySuggestions: List<ChatReplySuggestion> = emptyList(),
    val chatBranches: LocalChatBranchState = LocalChatBranchState(),
    val groupChat: LocalGroupChatState = LocalGroupChatState(),
    val groupActiveSpeakerName: String? = null,
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val personaCorrectionNotice: ChatPersonaCorrectionNotice? = null,

    val sessions: List<LocalSessionSummary> = emptyList(),
    val model: String = "deepseek-flash",
    val baseUrl: String = "https://api.deepseek.com",
    val modelProfiles: List<LocalModelProfile> = emptyList(),
    val planMode: Boolean = false,
    val safeAutoApprovalEnabled: Boolean = false,
    val deviceApprovalLease: Boolean = false,
    val queuedInputCount: Int = 0,
    val contextChars: Int = 0,
    val contextBudgetChars: Int = 0,
    val pendingApproval: LocalApproval? = null,
    val pendingQuestion: LocalQuestion? = null,
)

internal fun LocalHarnessState.toChatSurfaceUiState(): LocalConversationSurfaceState =
    LocalConversationSurfaceState(
        loading = loading,
        configured = modelState.configured,
        sessionId = sessionId,
        usageMode = LocalUsageMode.CHAT,
        running = running,
        messages = messages,
        workspacePath = workspacePath,
        error = error,
        galleryId = chat.galleryId,
        galleryStoryId = chat.galleryStoryId,
        chatPersona = chat.chatPersona,
        chatState = chat.chatState,
        replySuggestions = chat.replySuggestions,
        chatBranches = chat.chatBranches,
        groupChat = chat.groupChat,
        groupActiveSpeakerName = chat.groupActiveSpeakerName,
        conversationMode = conversationMode,
        personaCorrectionNotice = chat.personaCorrectionNotice,
    )

internal fun LocalHarnessState.toWorkSurfaceUiState(): LocalConversationSurfaceState =
    LocalConversationSurfaceState(
        loading = loading,
        configured = modelState.configured,
        sessionId = sessionId,
        usageMode = LocalUsageMode.WORK,
        running = running,
        messages = messages,
        workspacePath = workspacePath,
        imageInputMode = modelState.imageInputMode,
        error = error,
        sessions = sessions,
        model = modelState.model,
        baseUrl = modelState.baseUrl,
        modelProfiles = modelProfiles,
        planMode = work.planMode,
        safeAutoApprovalEnabled = safeAutoApprovalEnabled,
        deviceApprovalLease = deviceApprovalLease,
        queuedInputCount = queuedInputCount,
        contextChars = contextChars,
        contextBudgetChars = contextBudgetChars,
        pendingApproval = pendingApproval,
        pendingQuestion = pendingQuestion,
    )

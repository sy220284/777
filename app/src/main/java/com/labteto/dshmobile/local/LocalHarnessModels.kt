package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.model.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.work.LocalWorkState
import kotlinx.serialization.Serializable

@Serializable
enum class LocalUsageMode {
    CHAT,
    WORK,
}

data class LocalHarnessState(
    val loading: Boolean = true,
    val modelState: LocalModelState = LocalModelState(),
    val mainMaxSteps: Int = 16,
    val subagentMaxSteps: Int = 20,
    val workspacePath: String = "",
    val sessionId: String = "",
    val usageMode: LocalUsageMode = LocalUsageMode.WORK,
    val chat: LocalChatState = LocalChatState(),
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val parentSessionId: String? = null,
    val lineageId: String = "",
    val projectId: String? = null,
    val handoffSummary: String? = null,
    val userRules: String = "",
    val autoRecall: Boolean = true,
    val autoMemory: Boolean = true,
    val chatStyleGuardEnabled: Boolean = true,
    val chatStyleGuardCustomPhrases: List<String> = emptyList(),
    val styleGuardHits: List<String> = emptyList(),
    val sessions: List<LocalSessionSummary> = emptyList(),
    val messages: List<LocalHarnessMessage> = emptyList(),
    val transcriptIndex: LocalTranscriptRuntimeIndex = LocalTranscriptRuntimeIndex(),
    val work: LocalWorkState = LocalWorkState(),
    val kernel: LocalKernelState = LocalKernelState(),
    val safeAutoApprovalEnabled: Boolean = false,
    val deviceApprovalLease: Boolean = false,
    val usage: DeepSeekUsageSnapshot = DeepSeekUsageSnapshot(),
    val error: String? = null,
) {
    val modelProfiles: List<LocalModelProfile> get() = modelState.modelSelection.profiles
}

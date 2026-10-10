package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import kotlinx.coroutines.flow.StateFlow

/** Narrow Shell actions consumed by the HOME contribution; composition owns the ViewModel. */
internal data class LocalShellFeatureUiActions(
    val streamingState: StateFlow<LocalHarnessStreamingState>,
    val selectModel: (String) -> Unit,
    val send: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    val sendWithTeam: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    val teamMemberOutput: (String) -> String,
    val sendTeamMemberMessage: suspend (String, String) -> LocalWorkUiActionResult,
    val stopTeamMember: suspend (String) -> LocalWorkUiActionResult,
    val stopTeam: suspend () -> LocalWorkUiActionResult,
    val editAndResend: suspend (String, String) -> LocalChatUserEditResult,
    val selectMessageVariant: suspend (String, Int) -> Boolean,
    val regenerate: (String) -> Boolean,
    val generateReplySuggestions: suspend () -> Boolean,
    val loadOlderTranscript: suspend (String) -> Result<Int>,
    val importAttachment: suspend (Uri) -> LocalImportedAttachment,
    val stop: () -> Unit,
    val exitGroupChat: () -> Unit,
    val toggleSessionPinned: (String) -> Unit,
    val renameSession: (String, String) -> Boolean,
    val deleteSessions: suspend (Set<String>) -> Int,
    val configureChatPersona: suspend (PersonaProfile) -> Result<Unit>,
    val configureGroupMembers: (List<String>) -> Boolean,
    val selectGalleryPersona: (String) -> Boolean,
    val autoFillChatPersona: suspend (String) -> Result<PersonaProfile>,
    val saveGroupAnnouncement: suspend (String) -> Result<Unit>,
    val generateGroupAnnouncement: suspend (String) -> Result<String>,
    val undoPersonaCorrection: (Long, String, String) -> Unit,
    val setPlanMode: (Boolean) -> Unit,
    val approve: (String) -> Unit,
    val deny: (String) -> Unit,
    val enableAutoApproval: () -> Unit,
    val useDefaultApproval: () -> Unit,
    val setNetworkSearchEnabled: (Boolean) -> Unit,
    val enableAutoApprovalForPending: (String) -> Unit,
    val enableDeviceApprovalLease: (String) -> Unit,
    val disableDeviceApprovalLease: () -> Unit,
    val disableAutoApproval: () -> Unit,
    val answerQuestion: (String, String) -> Boolean,
    val cancelQuestion: (String) -> Unit,
    val usageRevision: StateFlow<Long>? = null,
    val turnUsageSummaries: (String) -> Map<String, com.labteto.dshmobile.local.TokenUsageAggregate> = { emptyMap() },
    val turnUsage: (String, String) -> com.labteto.dshmobile.local.TokenUsageGroupDetail? = { _, _ -> null },
    val requestUsage: (String) -> com.labteto.dshmobile.local.TokenUsageRecord? = { null },
)

/** Chat page actions only; no Feature contribution receives the aggregate ViewModel. */
internal data class LocalChatFeatureUiActions(
    val personaPresets: List<PersonaPreset>,
    val diaryEntries: suspend (String) -> List<ChatDiaryEntry>,
    val correctDiaryEntry: suspend (String, String, Long, ChatDiaryDelta) -> Boolean,
    val deactivateDiaryEntry: suspend (String, String, Long) -> Boolean,
    val hasUnsavedCurrentPersona: () -> Boolean,
    val currentGalleryHasUnsavedChanges: () -> Boolean,
    val saveCurrentToGallery: suspend (String, String?, String?, Boolean) -> Result<PersonaGalleryEntry>,
    val editGalleryStoryDetails: suspend (String, String, String, String) -> Result<Unit>,
    val renameGalleryStory: suspend (String, String, String) -> Result<Unit>,
    val inspectGalleryPersona: suspend (String, String?) -> Result<PersonaInspectionResult>,
    val applyGallerySuggestions: suspend (String, List<PersonaAppendSuggestion>) -> Result<PersonaGalleryEntry>,
    val deleteGalleryEntry: suspend (String) -> Result<Unit>,
    val deleteGalleryStory: suspend (String, String) -> Result<Unit>,
    val deleteGalleryHistoryMessage: suspend (String, String, String) -> Result<Unit>,
    val exportGalleryPersona: suspend (String, PersonaTransferFormat) -> Result<PersonaTransferDocument>,
    val importGalleryPersona: suspend (ByteArray, String?, String?) -> Result<PersonaGalleryEntry>,
    val installPersonaPreset: suspend (String) -> Result<PersonaGalleryEntry>,
    val setGalleryPortrait: suspend (String, Uri) -> Result<PersonaGalleryEntry>,
    val removeGalleryPortrait: suspend (String) -> Result<PersonaGalleryEntry>,
    val startFromGallery: (String, String?, Boolean) -> Boolean,
)

internal data class LocalWorkUiActionResult(
    val accepted: Boolean,
    val message: String,
)

internal data class LocalWorkFeatureUiActions(
    val workState: StateFlow<LocalWorkUiState>,
    val workspaceFiles: suspend () -> List<LocalWorkspaceFile>,
    val conversationFiles: suspend (String) -> LocalConversationFiles,
    val previewWorkspaceFile: suspend (String) -> LocalWorkspaceFilePreview,
    val backgroundJobOutput: (String) -> String,
    val artifactsForUi: (String) -> List<LocalArtifactUiItem>,
    val historyPageForUi: ((String, com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?) -> com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi)? = null,
    val artifactHistoryForUi: (String, Int) -> List<LocalArtifactUiItem> = { _, _ -> emptyList() },
    val toolActivitiesForUi: (String) -> List<LocalToolActivityUiItem>,
    val eventSequenceForUi: (String) -> Long,
    val toolEvidenceForUi: (String, String, Long) -> String? = { _, _, _ -> null },
    val requirementEvidenceForUi: (String) -> List<com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink> = { emptyList() },
    val linkRequirementEvidenceForUi: (String, Int, LocalArtifactUiItem) -> Boolean = { _, _, _ -> false },
    val stopBackgroundJob: (String) -> String,
    val startBackgroundAgent: suspend (String) -> LocalWorkUiActionResult,
    val startResearchAgent: suspend (String) -> LocalWorkUiActionResult,
    val sendBackgroundAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
    val usageRevision: StateFlow<Long>? = null,
    val sessionUsage: ((String) -> com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot)? = null,
    val taskUsage: (String) -> com.labteto.dshmobile.local.TokenUsageGroupDetail? = { null },
    val requestUsage: (String) -> com.labteto.dshmobile.local.TokenUsageRecord? = { null },
)

internal data class LocalAutomationFeatureUiActions(
    val switchSession: (String) -> Boolean,
)

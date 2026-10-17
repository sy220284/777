package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.ChatGroupUiPort
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.CurrentPersonaUiPort
import com.labteto.dshmobile.uicontract.DiaryUiPort
import com.labteto.dshmobile.uicontract.FilesUiPort
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MemoryUiPort
import com.labteto.dshmobile.uicontract.ModelPerformanceUiPort
import com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryUiPort
import com.labteto.dshmobile.uicontract.PersonaPortraitUiPort
import com.labteto.dshmobile.uicontract.PersonaSelectionUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort
import com.labteto.dshmobile.uicontract.PricingUiPort
import com.labteto.dshmobile.uicontract.ReplySuggestionsUiPort
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.ToolsUiPort
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiMessageHistoryPort
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uicontract.WorkEvidenceUiPort
import com.labteto.dshmobile.uicontract.WorkPolicyUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.WorkTeamUiPort
import kotlinx.coroutines.flow.Flow

/**
 * 朝夕会话域端口聚合（E8.11，字段形状对齐 V6ConversationPorts）。
 *
 * 由 App 组合根注入真实 Feature Ports；朝夕包不持有任何业务执行权，
 * 所有动作仅经端口 submit，回执只做呈现。
 * 空值语义：对应场景不声明、不渲染，UI 入口隐藏——绝不静默回退或伪造业务状态。
 */
data class ZhaoxiConversationPorts(
    val chat: ChatUiPort,
    val work: WorkConversationUiPort,
    val drafts: ConversationDraftUiPort,
    val reading: ConversationReadingUiPort,
    val attachments: AttachmentUiPort? = null,
    val history: UiMessageHistoryPort? = null,
    val documents: UiDocumentUiPort? = null,
    val forms: UiDocumentFormUiPort? = null,
    val media: MediaUiPort? = null,
    val evidence: WorkEvidenceUiPort? = null,
    val gallery: PersonaGalleryUiPort? = null,
    val selection: PersonaSelectionUiPort? = null,
    val groups: ChatGroupUiPort? = null,
    val stories: PersonaStoryUiPort? = null,
    val galleryDeletion: PersonaGalleryDeletionUiPort? = null,
    val personaAuthoring: PersonaAuthoringUiPort? = null,
    val workReads: WorkReadUiPort? = null,
    val workPolicy: WorkPolicyUiPort? = null,
    val workTeam: WorkTeamUiPort? = null,
    val replySuggestions: ReplySuggestionsUiPort? = null,
    val files: FilesUiPort? = null,
    val workMode: () -> Boolean,
    val workModeUpdates: Flow<Boolean>? = null,
)

/**
 * 朝夕次级域端口聚合（E8.11，字段形状对齐 V6SecondaryPorts）。
 *
 * 每个可选 Owner 都是 App 既有单例，绝不复制运行时。
 */
data class ZhaoxiSecondaryPorts(
    val tools: ToolsUiPort? = null,
    val files: FilesUiPort? = null,
    val media: MediaUiPort? = null,
    val settings: SettingsUiPort? = null,
    val sessionId: () -> String? = { null },
    val automation: AutomationUiPort? = null,
    val sessions: SessionUiPort? = null,
    val transcript: UiMessageHistoryPort? = null,
    val memory: MemoryUiPort? = null,
    val diary: DiaryUiPort? = null,
    val authoring: PersonaAuthoringUiPort? = null,
    val currentPersona: CurrentPersonaUiPort? = null,
    val replySuggestions: ReplySuggestionsUiPort? = null,
    val gallery: PersonaGalleryUiPort? = null,
    val selection: PersonaSelectionUiPort? = null,
    val portraits: PersonaPortraitUiPort? = null,
    val modelPerformance: ModelPerformanceUiPort? = null,
    val pricing: PricingUiPort? = null,
)

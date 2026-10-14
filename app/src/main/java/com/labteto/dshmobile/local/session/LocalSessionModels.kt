package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Persisted model history and its user-facing projection. */
@Serializable
data class LocalHarnessSession(
    val id: String = "",
    val title: String = "新会话",
    val updatedAt: Long = 0L,
    val usageMode: LocalUsageMode = LocalUsageMode.WORK,
    val personaId: String = "default",
    @SerialName("chatState")
    val chatStatePayload: JsonObject = JsonObject(emptyMap()),
    @SerialName("chatContext")
    val chatContextPayload: JsonObject = JsonObject(emptyMap()),
    @SerialName("replySuggestions")
    val replySuggestionsPayload: JsonArray = JsonArray(emptyList()),
    @SerialName("chatBranches")
    val chatBranchesPayload: JsonObject = JsonObject(emptyMap()),
    @SerialName("groupChat")
    val groupChatPayload: JsonObject = JsonObject(emptyMap()),
    val galleryId: String? = null,
    val galleryStoryId: String? = null,
    val gallerySaveSuppressedThrough: Long = 0L,
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val parentSessionId: String? = null,
    val lineageId: String = "",
    val projectId: String? = null,
    val handoffSummary: String? = null,
    /**
     * Legacy compatibility only. New snapshots leave this empty; Session Event is the complete
     * transcript source of truth.
     */
    val messages: List<LocalHarnessMessage> = emptyList(),
    /** Bounded startup/runtime cache. This is never allowed to become complete history. */
    val transcriptWindow: List<LocalHarnessMessage> = emptyList(),
    val transcriptIndex: LocalTranscriptRuntimeIndex = LocalTranscriptRuntimeIndex(),
    /**
     * Legacy compatibility only. New snapshots leave this empty; model-visible history is restored
     * from SessionEventLog checkpoints and semantic tail events.
     */
    @SerialName("modelHistory")
    val legacyModelHistory: List<JsonObject> = emptyList(),
    val plan: List<String> = emptyList(),
    @SerialName("todos")
    val todosPayload: JsonArray = JsonArray(emptyList()),
    @SerialName("goal")
    val goalPayload: JsonObject? = null,
    val planMode: Boolean = false,
    /** Highest SessionEvent sequence already reflected in the materialized control-state snapshot. */
    val controlProjectedThroughSequence: Long? = null,
    /** Highest SessionEvent sequence already reflected in the materialized user-facing transcript. */
    val transcriptProjectedThroughSequence: Long? = null,
)

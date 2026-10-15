package com.labteto.dshmobile.uicontract

import kotlinx.serialization.Serializable

val UI_PERSONA_FACT_FIELDS = listOf("personality", "valuesAndTradeoffs", "selfNarrative", "subjectiveBeliefs",
    "identityGap", "preferencesAndHabits", "voiceStyle", "biography", "relationships", "customFacts")

/** Immutable authoring values; no Chat stores, execution objects or mutable domain state. */
@Serializable
data class UiPersonaFact(val id: String, val category: String, val content: String,
    val relatedIds: List<String> = emptyList(), val perspective: String = "", val stage: String = "",
    val provenance: String = "UNVERIFIED", val source: String = "")
@Serializable
data class UiPersonaLore(val id: String, val title: String, val content: String,
    val keywords: List<String> = emptyList(), val secondaryKeywords: List<String> = emptyList(),
    val priority: Int = 50, val alwaysOn: Boolean = false, val spoilerLevel: Int = 0, val stage: String = "")
@Serializable
data class UiPersonaDraft(
    val name: String = "", val coreIdentity: String = "", val franchise: String = "",
    val fields: Map<String, String> = emptyMap(),
    val facts: List<UiPersonaFact> = emptyList(), val lore: List<UiPersonaLore> = emptyList(),
    val timelinePosition: String = "", val knowledgeBoundary: List<String> = emptyList(),
    val hardConstraints: List<String> = emptyList(), val bannedPhrases: List<String> = emptyList(),
    val corrections: List<String> = emptyList(), val presetId: String = "",
    val samplingTemperaturePosition: Int? = null,
    /** Compatibility position for old sampling overrides; not a new personality control. */
    val legacySamplingPosition: Int? = null,
)
data class UiPersonaAuthoringSnapshot(val galleryId: String, val revision: Long, val draft: UiPersonaDraft)
sealed interface UiPersonaAuthoringReceipt {
    data class Loaded(val snapshot: UiPersonaAuthoringSnapshot) : UiPersonaAuthoringReceipt
    data class Suggested(val draft: UiPersonaDraft) : UiPersonaAuthoringReceipt
    /** Saved archive is durable; applying it to a running conversation is a separate command. */
    data class Saved(val galleryId: String, val revision: Long) : UiPersonaAuthoringReceipt
    data class Rejected(val reason: UiCommandRejection) : UiPersonaAuthoringReceipt
    data class Failed(val message: String?) : UiPersonaAuthoringReceipt
}
data class UiPersonaPreset(val id: String, val name: String, val franchise: String, val summary: String)
data class UiPersonaConflict(val field: String, val fixedValue: String, val observedValue: String, val reason: String)
data class UiPersonaSuggestion(val field: String, val value: String, val evidence: String)
data class UiPersonaInspection(val galleryId: String, val revision: Long,
    val conflicts: List<UiPersonaConflict>, val suggestions: List<UiPersonaSuggestion>)
sealed interface UiPersonaInspectionReceipt {
    data class Inspected(val inspection: UiPersonaInspection) : UiPersonaInspectionReceipt
    data object Rejected : UiPersonaInspectionReceipt
    data class Failed(val message: String?) : UiPersonaInspectionReceipt
}
interface PersonaAuthoringUiPort {
    fun presets(): List<UiPersonaPreset> = emptyList()
    suspend fun installPreset(presetId: String): UiPersonaAuthoringReceipt =
        UiPersonaAuthoringReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
    suspend fun inspect(identity: UiSessionIdentity, galleryId: String, storyId: String?): UiPersonaInspectionReceipt =
        UiPersonaInspectionReceipt.Rejected
    /** Saves only the archive; selecting it for the current story is a separate explicit action. */
    suspend fun applySuggestions(galleryId: String, expectedRevision: Long,
        suggestions: List<UiPersonaSuggestion>): UiPersonaAuthoringReceipt =
        UiPersonaAuthoringReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
    suspend fun read(galleryId: String): UiPersonaAuthoringReceipt
    suspend fun create(draft: UiPersonaDraft): UiPersonaAuthoringReceipt
    suspend fun save(galleryId: String, expectedRevision: Long, draft: UiPersonaDraft): UiPersonaAuthoringReceipt
    /** Explicit model request; no automatic background completion or authority upgrade. */
    suspend fun suggest(identity: UiSessionIdentity, draft: UiPersonaDraft, description: String): UiPersonaAuthoringReceipt
}

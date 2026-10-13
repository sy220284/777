package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.presentation.document.LocalUiDocumentCodec
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import com.labteto.dshmobile.uicontract.UiSceneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean

internal const val UI_DOCUMENT_EVENT = "ui.document.published"
internal data class LocalUiDocumentRecord(val document: UiDocument, val provenance: UiDocumentProvenance)

/** Definitions live in the existing Session Event stream and share its deletion/archive lifecycle. */
@Singleton
internal class LocalUiDocumentSessionStore @Inject constructor(
    private val logs: LocalSessionEventLogRegistry,
) {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun publish(source: UiDocumentSource, definition: JsonObject, provenance: UiDocumentProvenance,
        pendingApprovalId: String?) {
        logs.get(source.sessionId).append(UI_DOCUMENT_EVENT, buildJsonObject {
            put("source", buildJsonObject {
                put("sessionId", source.sessionId); put("messageId", source.messageId)
                put("contextGeneration", source.contextGeneration); put("workMode", source.workMode)
                source.profileId?.let { put("profileId", it) }
                source.projectId?.let { put("projectId", it) }
            })
            put("definition", definition)
            put("provenance", buildJsonObject {
                provenance.requestId?.let { put("requestId", it) }
                put("model", provenance.model); put("usageReported", provenance.usageReported)
            })
            pendingApprovalId?.let { put("admittedApprovalId", it) }
        })
        mutableRevision.update { it + 1L }
    }

    fun read(sessionId: String, messageIds: Set<String>, budget: UiDocumentBudget,
        knownScenes: Set<UiSceneId>): List<LocalUiDocumentRecord> {
        if (messageIds.isEmpty()) return emptyList()
        val log = logs.get(sessionId)
        val found = log.latestMatchingByKey(setOf(UI_DOCUMENT_EVENT), messageIds) { event ->
            event.data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content
        }
        return found.values.sortedBy { it.sequence }.mapNotNull { event ->
            val value = event.data
            val anchor = value.getValue("source").jsonObject
            val source = UiDocumentSource(anchor.getValue("sessionId").jsonPrimitive.content,
                anchor.getValue("messageId").jsonPrimitive.content,
                anchor.getValue("contextGeneration").jsonPrimitive.long,
                anchor["profileId"]?.jsonPrimitive?.content, anchor["projectId"]?.jsonPrimitive?.content,
                anchor["workMode"]?.jsonPrimitive?.boolean ?: false)
            if (source.sessionId != sessionId) return@mapNotNull null
            val decoded = LocalUiDocumentCodec.decode(value.getValue("definition").toString(), source,
                budget, knownScenes, value["admittedApprovalId"]?.jsonPrimitive?.content).getOrNull()
                ?: return@mapNotNull null
            val provenance = value.getValue("provenance").jsonObject
            LocalUiDocumentRecord(decoded, UiDocumentProvenance(provenance["requestId"]?.jsonPrimitive?.content,
                provenance.getValue("model").jsonPrimitive.content, provenance.getValue("usageReported").jsonPrimitive.boolean))
        }
    }
}

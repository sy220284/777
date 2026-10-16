package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.presentation.document.LocalUiDocumentCodec
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.matches
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import com.labteto.dshmobile.uicontract.UiDocumentNode
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
internal data class LocalUiDocumentRecord(val document: UiDocument, val provenance: UiDocumentProvenance,
    val recoveryNotice: String? = null)

/** Definitions live in the existing Session Event stream and share its deletion/archive lifecycle. */
@Singleton
internal class LocalUiDocumentSessionStore @Inject constructor(
    private val logs: LocalSessionEventLogRegistry,
) {
    // One active, already bounded transcript query. No history-sized cache or second definition store.
    private data class ReadKey(val instance: String, val reset: Long, val revision: Long,
        val sessionId: String, val messageIds: Set<String>, val budget: UiDocumentBudget, val scenes: Set<UiSceneId>)
    private data class ReadCache(val key: ReadKey, val records: List<LocalUiDocumentRecord>)
    @Volatile private var lastRead: ReadCache? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    /** The Session sidecar owns revision admission for every producer, including local templates. */
    @Synchronized
    fun publish(document: UiDocument, provenance: UiDocumentProvenance, pendingApprovalId: String?,
        expected: UiDocument?, budget: UiDocumentBudget, knownScenes: Set<UiSceneId>): Boolean {
        val source = document.source
        val occupied = latestDocument(source.sessionId, source.messageId,
            document.documentId, budget, knownScenes)
        if (occupied?.recoveryNotice != null || occupied?.document?.source?.let { it != source } == true)
            return false
        val current = occupied?.document
        if (current != expected) return false
        if (current == null) {
            if (document.revision != 0L) return false
        } else if (!com.labteto.dshmobile.uicontract.UiDocumentPatch(document.documentId, current.revision, document).matches(current)) return false
        val definition = LocalUiDocumentCodec.encode(document)
        LocalUiDocumentCodec.decode(definition.toString(), source, budget, knownScenes, pendingApprovalId).getOrThrow()
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
                put("model", provenance.model); put("usageReported", provenance.usageReported); put("origin", provenance.origin.name)
            })
            pendingApprovalId?.let { put("admittedApprovalId", it) }
        })
        mutableRevision.update { it + 1L }
        return true
    }

    fun read(sessionId: String, messageIds: Set<String>, budget: UiDocumentBudget,
        knownScenes: Set<UiSceneId>): List<LocalUiDocumentRecord> {
        if (messageIds.isEmpty()) return emptyList()
        val log = logs.get(sessionId)
        val key = ReadKey(log.historyInstanceIdentity, log.resetGeneration, mutableRevision.value,
            sessionId, messageIds.toSet(), budget, knownScenes.toSet())
        lastRead?.takeIf { it.key == key }?.let { return it.records }
        // A user design, media summary and Work progress may share the same message.
        // Stream durable facts without creating a second persisted document authority.
        val found = log.withEvents { stream ->
            val newest = LinkedHashMap<Pair<String, String>, LocalSessionEventLog.Event>()
            stream.forEach { event ->
                if (event.type != UI_DOCUMENT_EVENT) return@forEach
                val anchor = runCatching {
                    event.data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content
                }.getOrNull() ?: return@forEach
                if (anchor !in messageIds) return@forEach
                val documentId = runCatching {
                    event.data["definition"]?.jsonObject?.get("documentId")?.jsonPrimitive?.content
                }.getOrNull() ?: "malformed:${event.sequence}"
                newest[anchor to documentId] = event
            }
            newest.values.sortedBy { it.sequence }
        }
        val records = found.mapNotNull { event ->
            decodeEvent(event, sessionId, messageIds, budget, knownScenes)
        }
        if (!log.isClosed && log.resetGeneration == key.reset && mutableRevision.value == key.revision) {
            lastRead = ReadCache(key, records)
        }
        return records
    }
    /** Indexed CAS lookup for one document, avoiding full Session history scans on every tool. */
    fun latestDocument(
        sessionId: String, messageId: String, documentId: String,
        budget: UiDocumentBudget, knownScenes: Set<UiSceneId>,
    ): LocalUiDocumentRecord? {
        if (sessionId.isBlank() || messageId.isBlank() || documentId.isBlank()) return null
        val event = logs.get(sessionId).latestMatching(setOf(UI_DOCUMENT_EVENT)) { data ->
            runCatching {
                data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content == messageId &&
                    data["definition"]?.jsonObject?.get("documentId")?.jsonPrimitive?.content == documentId
            }.getOrDefault(false)
        } ?: return null
        return decodeEvent(event, sessionId, setOf(messageId), budget, knownScenes)
    }

    private fun decodeEvent(
        event: LocalSessionEventLog.Event, sessionId: String,
        messageIds: Set<String>, budget: UiDocumentBudget, knownScenes: Set<UiSceneId>,
    ): LocalUiDocumentRecord? {
            val anchorId = runCatching { event.data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content }
                .getOrNull()?.takeIf { it in messageIds } ?: return null
            try {
                val value = event.data
                val anchor = value.getValue("source").jsonObject
                val source = UiDocumentSource(anchor.getValue("sessionId").jsonPrimitive.content,
                    anchor.getValue("messageId").jsonPrimitive.content,
                    anchor.getValue("contextGeneration").jsonPrimitive.long,
                    anchor["profileId"]?.jsonPrimitive?.content, anchor["projectId"]?.jsonPrimitive?.content,
                    anchor["workMode"]?.jsonPrimitive?.boolean ?: false)
                if (source.sessionId != sessionId) return null
                val definition = value.getValue("definition")
                val decoded = LocalUiDocumentCodec.decode(definition.toString(), source,
                    budget, knownScenes, value["admittedApprovalId"]?.jsonPrimitive?.content)
                val fallback = runCatching { definition.jsonObject["fallbackText"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
                    .takeIf { it.length <= budget.maxTextChars }.orEmpty()
                // An incompatible historical card is readable but cannot regain executable actions.
                val originalId = runCatching {
                    definition.jsonObject["documentId"]?.jsonPrimitive?.content
                }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 &&
                    it.none(Char::isISOControl) }
                val document = decoded.getOrNull() ?: UiDocument(1,
                    originalId ?: "unavailable:${event.sequence}", source, 0,
                    "", if (fallback.isBlank()) emptyList() else listOf(UiDocumentNode.Text("fallback", fallback)),
                    emptyList(), "")
                val provenance = value.getValue("provenance").jsonObject
                LocalUiDocumentRecord(document, UiDocumentProvenance(provenance["requestId"]?.jsonPrimitive?.content,
                    provenance.getValue("model").jsonPrimitive.content, provenance.getValue("usageReported").jsonPrimitive.boolean,
                    provenance["origin"]?.jsonPrimitive?.content?.let(com.labteto.dshmobile.uicontract.UiDocumentOrigin::valueOf)
                        ?: com.labteto.dshmobile.uicontract.UiDocumentOrigin.MODEL),
                    if (decoded.isFailure) "ui_document_history_incomplete" else null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                // A log-indexed message anchor is sufficient for read-only fallback, never actions.
                val fallback = runCatching { event.data["definition"]?.jsonObject?.get("fallbackText")?.jsonPrimitive?.content }
                    .getOrNull()?.takeIf { it.length <= budget.maxTextChars }.orEmpty()
                val source = UiDocumentSource(sessionId, anchorId, -1L, null, null)
                LocalUiDocumentRecord(UiDocument(1, "unavailable:${event.sequence}", source, 0L, "",
                    if (fallback.isBlank()) emptyList() else listOf(UiDocumentNode.Text("fallback", fallback)), emptyList(), ""),
                    UiDocumentProvenance(null, "", false, com.labteto.dshmobile.uicontract.UiDocumentOrigin.UNKNOWN), "ui_document_history_incomplete")
            }
    }

}

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
internal const val UI_DOCUMENT_DELETED_EVENT = "ui.document.deleted"
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
    // Only the current visible message set is indexed. No parallel persisted document store.
    // The projection is reconstructed from the Session EventLog, then advanced by sequence.
    private data class ReadCache(
        val key: ReadKey,
        val throughSequence: Long,
        val latestByDocument: Map<Pair<String, String>, LocalSessionEventLog.Event>,
        val records: List<LocalUiDocumentRecord>,
    )
    @Volatile private var lastRead: ReadCache? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    /** The Session sidecar owns revision admission for every producer, including local templates. */
    @Synchronized
    fun publish(document: UiDocument, provenance: UiDocumentProvenance, pendingApprovalId: String?,
        expected: UiDocument?, budget: UiDocumentBudget, knownScenes: Set<UiSceneId>): Boolean {
        val source = document.source
        // An explicit user tombstone is final for this ID. A delayed Work tool
        // callback must not resurrect a card that the user already deleted.
        val latest = latestDocumentEvent(source.sessionId, source.messageId, document.documentId)
        if (latest?.type == UI_DOCUMENT_DELETED_EVENT) return false
        val occupied = latest?.let {
            decodeEvent(it, source.sessionId, setOf(source.messageId), budget, knownScenes)
        }
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
        val revision = mutableRevision.value
        val key = ReadKey(log.historyInstanceIdentity, log.resetGeneration, revision,
            sessionId, messageIds.toSet(), budget, knownScenes.toSet())
        lastRead?.takeIf { it.key == key && it.throughSequence == log.latestSequence() }
            ?.let { return it.records }

        // Pin the durable sequence before opening any snapshot. An append racing this
        // projection is re-read on the next revision instead of being silently skipped.
        val checkpoint = log.latestSequence()
        val candidate = lastRead?.takeIf { old ->
            old.key.copy(revision = revision) == key && old.throughSequence <= checkpoint
        }
        fun indexKey(event: LocalSessionEventLog.Event): Pair<String, String>? {
            if (event.type != UI_DOCUMENT_EVENT && event.type != UI_DOCUMENT_DELETED_EVENT) return null
            val anchor = runCatching {
                event.data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it in messageIds } ?: return null
            val documentId = runCatching {
                if (event.type == UI_DOCUMENT_DELETED_EVENT)
                    event.data["documentId"]?.jsonPrimitive?.content
                else event.data["definition"]?.jsonObject?.get("documentId")?.jsonPrimitive?.content
            }.getOrNull() ?: "malformed:${event.sequence}"
            return anchor to documentId
        }
        val indexed: Map<Pair<String, String>, LocalSessionEventLog.Event> =
            if (candidate != null) {
                // A single visible window normally survives hundreds of Work tool events.
                // Catch up from the last observed EventLog cursor, not from the first event.
                val next = LinkedHashMap(candidate.latestByDocument)
                var cursor = candidate.throughSequence
                var continuity = true
                while (cursor < checkpoint) {
                    val page = log.pageAfter(cursor, limit = 128)
                    if (page.isEmpty() || page.last().sequence <= cursor) {
                        continuity = false
                        break
                    }
                    page.forEach { event -> indexKey(event)?.let { next[it] = event } }
                    cursor = page.last().sequence
                }
                if (continuity) next else log.withEvents { stream ->
                    val rebuild = LinkedHashMap<Pair<String, String>, LocalSessionEventLog.Event>()
                    stream.forEach { event -> indexKey(event)?.let { rebuild[it] = event } }
                    rebuild
                }
            } else log.withEvents { stream ->
                // An uncached/after-restart read is exact, including historical revisions.
                // Only the latest event for each visible (message, document) is retained.
                val rebuild = LinkedHashMap<Pair<String, String>, LocalSessionEventLog.Event>()
                stream.forEach { event -> indexKey(event)?.let { rebuild[it] = event } }
                rebuild
            }

        val records = indexed.values.asSequence()
            .filter { it.type == UI_DOCUMENT_EVENT }
            .sortedBy { it.sequence }
            .mapNotNull { event -> decodeEvent(event, sessionId, messageIds, budget, knownScenes) }
            .toList()
        if (!log.isClosed && log.resetGeneration == key.reset && mutableRevision.value == revision) {
            lastRead = ReadCache(key, checkpoint, indexed, records)
        }
        return records
    }
    /** Explicit user deletion is a versioned event in the same Session authority. */
    @Synchronized
    fun delete(document: UiDocument, budget: UiDocumentBudget,
        knownScenes: Set<UiSceneId>): Boolean {
        val source = document.source
        val current = latestDocument(source.sessionId, source.messageId,
            document.documentId, budget, knownScenes)
            ?.takeIf { it.recoveryNotice == null }?.document ?: return false
        if (current != document) return false
        logs.get(source.sessionId).append(UI_DOCUMENT_DELETED_EVENT, buildJsonObject {
            put("source", buildJsonObject {
                put("sessionId", source.sessionId)
                put("messageId", source.messageId)
                put("contextGeneration", source.contextGeneration)
            })
            put("documentId", document.documentId)
            put("deletedRevision", document.revision)
        })
        mutableRevision.update { it + 1L }
        return true
    }

    private fun latestDocumentEvent(sessionId: String, messageId: String,
        documentId: String): LocalSessionEventLog.Event? =
        logs.get(sessionId).latestMatching(setOf(UI_DOCUMENT_EVENT, UI_DOCUMENT_DELETED_EVENT)) { data ->
            runCatching {
                data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content == messageId &&
                    (data["documentId"]?.jsonPrimitive?.content
                        ?: data["definition"]?.jsonObject?.get("documentId")?.jsonPrimitive?.content) == documentId
            }.getOrDefault(false)
        }

    /** Indexed CAS lookup for one document, honoring durable user tombstones. */
    fun latestDocument(
        sessionId: String, messageId: String, documentId: String,
        budget: UiDocumentBudget, knownScenes: Set<UiSceneId>,
    ): LocalUiDocumentRecord? {
        if (sessionId.isBlank() || messageId.isBlank() || documentId.isBlank()) return null
        val event = latestDocumentEvent(sessionId, messageId, documentId) ?: return null
        if (event.type == UI_DOCUMENT_DELETED_EVENT) return null
        return decodeEvent(event, sessionId, setOf(messageId), budget, knownScenes)
    }

    private fun decodeEvent(
        event: LocalSessionEventLog.Event, sessionId: String,
        messageIds: Set<String>, budget: UiDocumentBudget, knownScenes: Set<UiSceneId>,
    ): LocalUiDocumentRecord? {
        val anchorId = runCatching { event.data["source"]?.jsonObject?.get("messageId")?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it in messageIds } ?: return null
        return try {
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

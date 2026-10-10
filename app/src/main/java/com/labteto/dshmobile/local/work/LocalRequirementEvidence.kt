package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.work.LocalTodoItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** User-selected evidence reference. The link by itself does not assert a test or task passed. */
internal data class LocalRequirementEvidenceLink(
    val requirementIndex: Int,
    val requirement: String,
    val artifactPath: String,
    val artifactSequence: Long,
    val sourceCallId: String,
    val versionAtLink: String,
    val eventSequence: Long,
)

internal fun projectRequirementEvidenceLinks(
    events: List<LocalSessionEventLog.Event>,
    todos: List<LocalTodoItem>,
): List<LocalRequirementEvidenceLink> {
    val linked = linkedMapOf<Pair<Int, String>, LocalRequirementEvidenceLink>()
    events.forEach { event ->
        if (event.type != "work/requirement-evidence") return@forEach
        val data = event.data
        val index = (data["requirement_index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
        val text = (data["requirement"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        val path = (data["artifact_path"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        val version = (data["sha256"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        val origin = (data["origin_sequence"] as? JsonPrimitive)?.longOrNull ?: return@forEach
        val call = (data["source_call_id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        if (index !in todos.indices || todos[index].content != text ||
            !version.matches(Regex("[a-f0-9]{64}")) || origin <= 0L || call.isBlank()
        ) return@forEach
        linked[index to path] = LocalRequirementEvidenceLink(
            index, text, path, origin, call, version, event.sequence,
        )
    }
    return linked.values.toList().sortedBy(LocalRequirementEvidenceLink::requirementIndex)
}

/** Exact selected source event; no fuzzy matching by filename or task wording. */
internal fun sourceArtifactMatchesEvidence(
    event: LocalSessionEventLog.Event?,
    expected: LocalArtifactUiItem,
): Boolean {
    if (event == null || expected.category != "file" ||
        event.sequence != expected.asOfSequence) return false
    return projectLocalWorkArtifacts(listOf(event)).any { artifact ->
        artifact.reference == expected.reference && artifact.sourceCallId == expected.sourceCallId &&
            artifact.asOfSequence == expected.asOfSequence
    }
}

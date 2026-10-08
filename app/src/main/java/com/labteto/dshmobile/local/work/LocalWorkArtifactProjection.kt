package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Work-facing projection over authoritative Session EventLog results. */
internal fun projectLocalWorkArtifacts(
    events: List<LocalSessionEventLog.Event>,
    limit: Int = 40,
): List<LocalArtifactUiItem> {
    val results = linkedMapOf<String, LocalArtifactUiItem>()
    events.forEach { event ->
        if (event.type !in setOf("tool/result", "subagent/tool-result")) return@forEach
        val data = event.data
        if ((data["is_error"] as? JsonPrimitive)?.contentOrNull == "true") return@forEach
        if ((data["error_code"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) return@forEach
        val callId = (data["id"] as? JsonPrimitive)?.contentOrNull
        for ((key, kind) in listOf("path" to "file", "artifact_path" to "file", "url" to "link", "commit_sha" to "revision")) {
            val raw = (data[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
            if (raw.length !in 1..480) continue
            if (kind == "file" && (raw.startsWith("/") || raw.split('/').any { it == ".." })) continue
            if (kind == "link" && !(raw.startsWith("https://") || raw.startsWith("http://"))) continue
            if (kind == "revision" && !raw.matches(Regex("[a-fA-F0-9]{7,64}"))) continue
            results[raw] = LocalArtifactUiItem(
                reference = raw,
                category = kind,
                sourceCallId = callId,
                asOfSequence = event.sequence,
            )
        }
    }
    return results.values.sortedByDescending(LocalArtifactUiItem::asOfSequence).take(limit.coerceIn(1, 100))
}

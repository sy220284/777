package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
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
        val declared = listOf("path" to "file", "artifact_path" to "file", "url" to "link", "commit_sha" to "revision")
            .mapNotNull { (key, kind) ->
                (data[key] as? JsonPrimitive)?.contentOrNull?.trim()?.let { it to kind }
            }
        val verified = verifiedLocalWorkspaceResultPath(data)?.let { listOf(it to "file") }.orEmpty()
        for ((raw, kind) in declared + verified) {
            if (raw.length !in 1..480) continue
            if (kind == "file" && !isSafeLocalArtifactPath(raw)) continue
            if (kind == "link" && !isSafeArtifactUrl(raw)) continue
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


/** Only known workspace tools and their exact post-success outputs can declare artifacts. */
private fun verifiedLocalWorkspaceResultPath(data: JsonObject): String? {
    val name = (data["name"] as? JsonPrimitive)?.contentOrNull ?: return null
    val content = (data["content"] as? JsonPrimitive)?.contentOrNull ?: return null
    val path = when (name) {
        "write", "write_file" ->
            Regex("""^已写入 (.+)（[0-9]+ 字节）$""").matchEntire(content)?.groupValues?.get(1)
        "edit", "edit_file" ->
            Regex("""^已编辑 ([^\r\n]+)$""").matchEntire(content)?.groupValues?.get(1)
        "present" ->
            Regex("""^成果已确认：(.+)（[0-9]+ 字节）$""").matchEntire(content)?.groupValues?.get(1)
        else -> null
    }
    return path?.trim()?.takeIf(String::isNotBlank)
}

/** Tool result metadata is untrusted: never display absolute or parent paths as workspace files. */
private fun isSafeLocalArtifactPath(path: String): Boolean =
    path.isNotBlank() &&
        !path.startsWith("/") &&
        !path.contains('\\') &&
        !path.contains(':') &&
        path.none { it.code < 0x20 || it.code == 0x7f } &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

/** An artifact link needs a real HTTP(S) authority and must not expose embedded credentials. */
private fun isSafeArtifactUrl(raw: String): Boolean = runCatching {
    val uri = java.net.URI(raw)
    uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        raw.none { it.code < 0x20 || it.code == 0x7f }
}.getOrDefault(false)

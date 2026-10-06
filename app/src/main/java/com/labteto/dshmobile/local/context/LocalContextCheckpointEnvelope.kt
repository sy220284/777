package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal enum class LocalContextCheckpointKind {
    WORK,
    CHAT,
}

private const val CONTEXT_CHECKPOINT_PROVENANCE_KEY = "_dsh_context_checkpoint_source"
private const val CONTEXT_CHECKPOINT_KIND_KEY = "_dsh_context_checkpoint_kind"
private const val CONTEXT_CHECKPOINT_PROVENANCE_VALUE = "history_compactor_v2"
private const val LEGACY_WORK_CHECKPOINT_PROVENANCE_KEY = "_dsh_work_checkpoint_source"
private const val LEGACY_WORK_CHECKPOINT_PROVENANCE_VALUE = "history_compactor_v1"

internal fun LocalHistorySummaryMode.checkpointKind(): LocalContextCheckpointKind = when (this) {
    LocalHistorySummaryMode.WORK -> LocalContextCheckpointKind.WORK
    LocalHistorySummaryMode.CHAT -> LocalContextCheckpointKind.CHAT
}

internal fun LocalUsageMode.historySummaryMode(): LocalHistorySummaryMode = when (this) {
    LocalUsageMode.WORK -> LocalHistorySummaryMode.WORK
    LocalUsageMode.CHAT -> LocalHistorySummaryMode.CHAT
}

internal fun buildTrustedContextCheckpointModelMessage(
    summaryMode: LocalHistorySummaryMode,
    content: String,
): JsonObject = buildTrustedContextCheckpointModelMessage(summaryMode.checkpointKind(), content)

internal fun buildTrustedContextCheckpointModelMessage(
    kind: LocalContextCheckpointKind,
    content: String,
): JsonObject = buildJsonObject {
    put("role", "user")
    put(CONTEXT_CHECKPOINT_PROVENANCE_KEY, CONTEXT_CHECKPOINT_PROVENANCE_VALUE)
    put(CONTEXT_CHECKPOINT_KIND_KEY, kind.name.lowercase())
    put("content", content)
}

/** Source-compatible alias while old Work recovery/tests migrate to the shared envelope. */
internal fun buildTrustedWorkCheckpointModelMessage(content: String): JsonObject =
    buildTrustedContextCheckpointModelMessage(LocalContextCheckpointKind.WORK, content)

internal fun isTrustedContextCheckpointModelMessage(
    message: JsonObject,
    kind: LocalContextCheckpointKind? = null,
): Boolean {
    if ((message["role"] as? JsonPrimitive)?.contentOrNull != "user") return false

    val source = (message[CONTEXT_CHECKPOINT_PROVENANCE_KEY] as? JsonPrimitive)?.contentOrNull
    if (source == CONTEXT_CHECKPOINT_PROVENANCE_VALUE) {
        val actualKind = (message[CONTEXT_CHECKPOINT_KIND_KEY] as? JsonPrimitive)
            ?.contentOrNull
            ?.let { value ->
                LocalContextCheckpointKind.entries.firstOrNull {
                    it.name.equals(value, ignoreCase = true)
                }
            }
            ?: return false
        return kind == null || actualKind == kind
    }

    val legacyWork = (message[LEGACY_WORK_CHECKPOINT_PROVENANCE_KEY] as? JsonPrimitive)?.contentOrNull ==
        LEGACY_WORK_CHECKPOINT_PROVENANCE_VALUE
    return legacyWork && (kind == null || kind == LocalContextCheckpointKind.WORK)
}

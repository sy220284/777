package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Recover presentation provenance from durable Work run facts, never the currently selected message. */
internal fun resolveWorkUiDocumentSource(log: LocalSessionEventLog, inputId: String?): String? {
    if (inputId.isNullOrBlank()) return null
    val recoveryRunId = inputId.takeIf { it.startsWith("run-recovery:") }
        ?.removePrefix("run-recovery:")
    if (recoveryRunId == null && !inputId.startsWith("continuation-")) return inputId
    var runId = recoveryRunId
    var cursor: Long? = null
    while (true) {
        val page = log.pageBeforeNewestFirst(sequenceExclusive = cursor ?: Long.MAX_VALUE, limit = 128)
        if (page.isEmpty()) return null // Without provenance, never invent an anchor.
        for (event in page) {
            if (runId == null && event.type == "turn/continuation-queued" &&
                event.data["continuation_id"]?.jsonPrimitive?.contentOrNull == inputId) {
                runId = event.data["source_run_id"]?.jsonPrimitive?.contentOrNull
            }
            if (runId != null && event.type == "turn/start" &&
                event.data["run_id"]?.jsonPrimitive?.contentOrNull == runId) {
                return event.data["source_message_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
            }
        }
        cursor = page.last().sequence
    }
}

/** A continuation with missing durable provenance must never borrow the latest user turn. */
internal fun workDocumentSourceOrLastUser(
    inputId: String?, resolved: String?, lastUser: String?,
): String? = resolved ?: if (inputId?.startsWith("continuation-") == true ||
    inputId?.startsWith("run-recovery:") == true) null else lastUser

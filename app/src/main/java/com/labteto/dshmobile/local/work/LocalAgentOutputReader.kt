package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Work result projection; callers resolve the Job's session ownership before reading. */
internal fun readLocalAgentOutput(log: LocalSessionEventLog, registration: LocalJobRegistration): String {
    // Job output is a bounded progress/status projection, not the durable Agent deliverable.
    // A fresh activation must never show an older activation's answer as its new result.
    if (registration.info.status !in setOf("completed", "dormant")) return registration.snapshot.output
    val event = log.latestMatching(setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT)) {
        it["background_job_id"]?.jsonPrimitive?.contentOrNull == registration.info.id
    } ?: error("任务结果记录缺失，请检查该任务的会话记录")
    val checkpoint = decodeLocalSubagentHistoryCheckpoint(event.data)
        ?: error("任务结果记录无法解析，请检查该任务的诊断信息")
    val output = checkpoint.terminalOutput ?: error("任务尚无已提交的最终结果，请刷新任务状态")
    // The old wire format silently retained only the tail. Preserve its readable evidence
    // while clearly identifying the loss; a v3 result is always the complete original value.
    val version = event.data["version"]?.jsonPrimitive?.intOrNull ?: 1
    return if (version < 3 && output.length >= 65_536)
        "历史版本仅保存了结果末段，以下内容可能不完整：\n$output"
    else output
}

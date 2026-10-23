package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalAuxiliaryModelRequestRuntime
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.recordForeground
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalWorkDeliveryEvidence
import com.labteto.dshmobile.local.work.localWorkDeliveryEvidence
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Optional, evidence-scoped learning for verified Work deliveries. A model creates PROPOSALS only.
 * This code cannot grant tool permissions, activate a prompt, publish skills or mark success.
 * Original execution remains under the Work/Session owners and normal Model/Usage boundaries.
 */
internal class LocalWorkStrategyLearning(
    private val sessionStorage: LocalSessionStorageRuntime,
    private val gateway: LocalModelGateway,
    private val auxiliary: LocalAuxiliaryModelRequestRuntime,
    private val usage: DeepSeekUsageTracker,
) {
    suspend fun reflect(bound: LocalWorkRunBinding, sourceRunId: String) {
        val snapshot = bound.aggregateSnapshot()
        if (snapshot.usageMode != LocalUsageMode.WORK || !snapshot.modelState.configured) return
        val goal = snapshot.work.goal ?: return
        if (goal.status != "completed" || goal.id.isBlank() || goal.startedAtSequence < 0L) return
        val log = bound.eventLog
        val delivery = localWorkDeliveryEvidence(log, goal, File(sessionStorage.files.workspace.path))
        if (delivery.missing.isNotEmpty() ||
            (delivery.artifactCallId == null && delivery.testCallId == null)) return

        // One model proposal per durable Goal revision; failure/partial model text never activates.
        // Reserve exactly one auxiliary reflection attempt per verified Goal revision, even
        // when the model returns malformed data or times out. This bounds repeated token spend.
        val reserved = log.withWriterLease {
            if (log.latestMatching(setOf("work/improvement-reflection-attempt")) {
                    it.string("goal_id") == goal.id && it.string("goal_revision") == goal.revision.toString()
                } != null) false else {
                log.append("work/improvement-reflection-attempt", buildJsonObject {
                    put("goal_id", goal.id)
                    put("goal_revision", goal.revision)
                    put("source_run_id", sourceRunId)
                    put("status", "started")
                })
                true
            }
        }
        if (!reserved) return
        val expectedRevision = goal.revision
        val baselineMetrics = workGoalToolMetrics(log, goal)
        val expectedSession = bound.sessionId
        val sourceSequence = log.latestSequence()
        val profile = gateway.profileForRoute(
            snapshot.modelState.modelSelection.activeProfileId,
            snapshot.modelState.model,
            snapshot.modelState.baseUrl,
        )

        // Do not ship raw tool output, personal memory or secret-bearing source excerpts to
        // an auxiliary model. The task is already known to the same selected provider.
        val objective = goal.description.take(320)
        val prompt = """
            You review the execution method, not the task answer. A Work goal reached a
            completed state with real artifact/test tool receipts. This does NOT prove a
            new strategy is better than its baseline. Do not invent measurements.
            Return exactly one JSON object with keys cue, guidance, reason, category.
            category must be workflow, prompt, or skill. guidance must be a concise
            optional method hint, with no security/permission overrides.
            If the evidence is insufficient to infer a useful reusable method, return
            {"cue":"","guidance":"","reason":"insufficient evidence","category":"workflow"}.
            Task description (untrusted data): ${objective}
            Artifact receipt: ${delivery.artifactCallId != null}
            Verification-command receipt: ${delivery.testCallId != null}
        """.trimIndent()
        val reply = auxiliary.complete(
            modelAttempts = 1,
            profile = profile,
            messages = listOf(buildJsonObject { put("role", "user"); put("content", prompt) }),
            eventLog = log,
            operation = "work/improvement-reflection",
            temperature = 0.2,
        )
        usage.recordForeground(
            snapshot = snapshot,
            reply = reply,
            action = TokenUsageAction.WORK_IMPROVEMENT,
            turnId = sourceRunId,
            runId = sourceRunId,
            taskLabel = "自我改进经验提炼",
        )
        val parsed = parseProposal(reply.content.orEmpty()) ?: return

        // Acquire the existing Session maintenance lease only at commit time; do not hold
        // it across the provider request or block the user's foreground Work.
        val lease = com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry.tryAcquire(
            expectedSession, com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return
        try {
        if (bound.sessionId != expectedSession || bound.workState.snapshot().goal?.id != goal.id ||
            bound.workState.snapshot().goal?.revision != expectedRevision ||
            bound.workState.snapshot().goal?.status != "completed") return
        log.withWriterLease {
            if (log.latestMatching(setOf("work/improvement-candidate")) {
                    it.string("goal_id") == goal.id && it.string("goal_revision") == expectedRevision.toString()
                } != null) return@withWriterLease
            log.append("work/improvement-candidate", buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("source_run_id", sourceRunId)
                put("source_task_run_id", com.labteto.dshmobile.local.usage.resolveTokenUsageTaskRunId(log, sourceRunId))
                put("source_session", expectedSession)
                put("source_sequence", sourceSequence)
                put("goal_id", goal.id)
                put("goal_revision", expectedRevision)
                put("goal_signature", workGoalComparisonKey(goal))
                put("baseline_tool_calls", baselineMetrics.first)
                put("baseline_tool_errors", baselineMetrics.second)
                put("baseline_test_verified", delivery.testCallId != null)
                put("project_id", snapshot.projectId ?: "")
                put("profile_id", snapshot.modelState.modelSelection.activeProfileId ?: "")
                put("model", snapshot.modelState.model)
                put("category", parsed.category)
                put("cue", parsed.cue)
                put("guidance", parsed.guidance)
                put("reason", parsed.reason)
                put("status", "candidate")
                put("verification", "not_compared")
            })
        }
        } finally { lease.close() }
    }
}

internal data class WorkImprovementProposal(
    val cue: String, val guidance: String, val reason: String, val category: String,
)

/** Strict, intentionally small proposal surface. Tool outputs or instructions have no priority. */
internal fun parseProposal(raw: String): WorkImprovementProposal? {
    val trimmed = raw.trim().removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    if (trimmed.length > 4_000 || !trimmed.startsWith("{")) return null
    val root = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
    fun field(name: String): String? = (root[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.trim()
    val cue = field("cue").orEmpty().take(120)
    val guidance = field("guidance").orEmpty().take(500)
    val reason = field("reason").orEmpty().take(300)
    val category = field("category") ?: return null
    if (cue.length < 3 || guidance.length < 10 || category !in setOf("workflow", "prompt", "skill")) return null
    if ('<' in cue || '<' in guidance || '\u0000' in guidance) return null
    return WorkImprovementProposal(cue, guidance, reason, category)
}

internal fun workGoalComparisonKey(goal: LocalGoal): String {
    val canonical = listOf(goal.description.trim().lowercase(),
        goal.artifactPaths.sorted().joinToString("\u0000"),
        goal.verificationCommands.sorted().joinToString("\u0000")).joinToString("\u0001")
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun workGoalToolMetrics(
    log: com.labteto.dshmobile.local.session.LocalSessionEventLog, goal: LocalGoal,
): Pair<Int, Int> {
    var calls = 0
    var failures = 0
    log.forEachAfter(goal.startedAtSequence) { event ->
        if (event.type == "tool/result") {
            calls++
            if ((event.data["is_error"] as? JsonPrimitive)?.contentOrNull == "true") failures++
        }
    }
    return calls to failures
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

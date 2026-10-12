package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Positive provenance over the existing durable session event stream; never a second mutable
 * completion ledger. Artifact digests and tool results must come from real execution events,
 * not assertions in assistant text or a model-generated checklist.
 */
internal data class LocalWorkDeliveryEvidence(
    val needsArtifact: Boolean,
    val needsTest: Boolean,
    val successfulToolCallId: String? = null,
    val artifactCallId: String? = null,
    val testCallId: String? = null,
) {
    val missing: List<String> get() = buildList {
        if (needsArtifact && artifactCallId == null)
            add("缺少本次目标的真实文件版本或可核验提交证据")
        if (needsTest && testCallId == null)
            add("缺少本次目标的实际测试/构建成功结果")
    }
}

private fun JsonObject.value(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun localWorkDeliveryEvidence(
    log: LocalSessionEventLog,
    goalDescription: String,
): LocalWorkDeliveryEvidence {
    val needsArtifact = Regex("""(?i)(?:代码|文件|仓库|应用|app|ui|组件|模块).{0,48}(?:修改|改造|实现|修复|重构|替换|开发)|(?:修改|改造|实现|修复|重构|替换|开发).{0,48}(?:代码|文件|仓库|应用|app|ui|组件|模块)""")
        .containsMatchIn(goalDescription)
    val needsTest = Regex("""(?i)(?:单元测试|仪表测试|回归测试|构建|编译|CI\b|\btests?\b|\bbuild\b)""")
        .containsMatchIn(goalDescription)

    if (!needsArtifact && !needsTest) return LocalWorkDeliveryEvidence(false, false)
    // Last active transition is the current goal's evidence horizon. Older task artifacts
    // cannot silently satisfy a newly created/updated goal with the same description.
    val startSequence = log.latestMatching(setOf("goal/state")) { event ->
        event.value("description") == goalDescription && event.value("status") == "active"
    }?.sequence ?: -1L
    if (startSequence < 0L) return LocalWorkDeliveryEvidence(needsArtifact, needsTest)

    val successful = log.latestMatching(setOf("tool/result")) { event ->
        event.value("is_error") == "false" && !event.value("id").isNullOrBlank()
    }?.takeIf { it.sequence > startSequence }
    val artifact = if (needsArtifact) log.latestMatching(setOf("tool/result")) { event ->
        event.value("is_error") == "false" &&
            !event.value("artifact_path").isNullOrBlank() &&
            event.value("artifact_sha256")?.matches(Regex("""^[a-fA-F0-9]{64}$""")) == true &&
            !event.value("id").isNullOrBlank()
    }?.takeIf { it.sequence > startSequence } else null

    val test = if (needsTest) {
        log.latestMatching(setOf("tool/result")) { event ->
            event.value("is_error") == "false" &&
                event.value("name") in setOf("bash", "run_shell", "terminal_exec", "run_command") &&
                Regex("""(?i)(BUILD SUCCESSFUL|tests? passed|[1-9]\d* passed)""")
                    .containsMatchIn(event.value("content").orEmpty()) &&
                !event.value("id").isNullOrBlank()
        }?.takeIf { result ->
            if (result.sequence <= startSequence) return@takeIf false
            val callId = result.data.value("id")
            val call = log.latestMatching(
                setOf("tool/call"), beforeSequenceExclusive = result.sequence,
            ) { event -> event.value("id") == callId }
            val command = call?.data?.get("arguments")?.toString().orEmpty()
            call != null && call.sequence > startSequence &&
                Regex("""(?i)(?:gradlew?|pytest|npm|pnpm|yarn|swift)\b.{0,200}(?:test|check|build|assemble|lint)|(?:^|[^a-z])pytest\b""")
                    .containsMatchIn(command) &&
                !Regex("""(?i)\becho\b""").containsMatchIn(command)
        }
    } else null
    return LocalWorkDeliveryEvidence(
        needsArtifact = needsArtifact, needsTest = needsTest,
        successfulToolCallId = successful?.data?.value("id"),
        artifactCallId = artifact?.data?.value("id"),
        testCallId = test?.data?.value("id"),
    )
}

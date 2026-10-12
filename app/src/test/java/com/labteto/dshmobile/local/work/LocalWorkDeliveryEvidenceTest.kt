package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkDeliveryEvidenceTest {
    @Test fun completedTodoWithoutRealArtifactIsNotEnoughForCodeGoal() {
        val root = Files.createTempDirectory("goal-positive-evidence").toFile()
        try {
            val log = LocalSessionEventLog(File(root, "events.jsonl"), Json)
            val goal = "修改应用文件并完成单元测试"
            log.append("goal/state", buildJsonObject { put("description", goal); put("status", "active") })
            val noProof = localWorkDeliveryEvidence(log, goal)
            assertEquals(2, noProof.missing.size)
            val work = LocalWorkState(
                goal = LocalGoal(goal),
                todos = listOf(LocalTodoItem("实现和测试", "completed")),
            )
            assertFalse(resolveWorkGoalTransition(
                requireNotNull(work.goal), requireNotNull(work.goal).copy(status = "completed"),
                work, noProof,
            ).accepted)

            log.append("tool/call", buildJsonObject {
                put("id", "file-write")
                put("name", "write")
            })
            log.append("tool/result", buildJsonObject {
                put("id", "file-write")
                put("name", "write")
                put("is_error", false)
                put("artifact_path", "src/main.kt")
                put("artifact_sha256", "a".repeat(64))
            })
            val onlyArtifact = localWorkDeliveryEvidence(log, goal)
            assertEquals("file-write", onlyArtifact.artifactCallId)
            assertEquals(null, onlyArtifact.testCallId)
            assertFalse(resolveWorkGoalTransition(
                requireNotNull(work.goal), requireNotNull(work.goal).copy(status = "completed"),
                work, onlyArtifact,
            ).accepted)

            log.append("tool/call", buildJsonObject {
                put("id", "actual-test")
                put("name", "bash")
                put("arguments", buildJsonObject { put("command", "./gradlew testDebugUnitTest") })
            })
            log.append("tool/result", buildJsonObject {
                put("id", "actual-test")
                put("name", "bash")
                put("is_error", false)
                put("content", "BUILD SUCCESSFUL in 9s")
            })
            val verified = localWorkDeliveryEvidence(log, goal)
            assertTrue(verified.missing.isEmpty())
            assertEquals("actual-test", verified.testCallId)
            assertTrue(resolveWorkGoalTransition(
                requireNotNull(work.goal), requireNotNull(work.goal).copy(status = "completed"),
                work, verified,
            ).accepted)
            log.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun artifactOutputWithoutMatchingInvocationInCurrentGoalCannotPassAcceptance() {
        val root = Files.createTempDirectory("goal-evidence-correlation").toFile()
        try {
            val log = LocalSessionEventLog(File(root, "events.jsonl"), Json)
            val goal = "修改代码文件"
            log.append("tool/call", buildJsonObject {
                put("id", "old-call"); put("name", "write")
            })
            log.append("goal/state", buildJsonObject {
                put("description", goal); put("status", "active")
            })
            log.append("tool/result", buildJsonObject {
                put("id", "old-call"); put("name", "write"); put("is_error", false)
                put("artifact_path", "src/main.kt"); put("artifact_sha256", "b".repeat(64))
            })
            assertEquals(null, localWorkDeliveryEvidence(log, goal).artifactCallId)
            log.append("tool/result", buildJsonObject {
                put("id", "no-call"); put("name", "write"); put("is_error", false)
                put("artifact_path", "src/other.kt"); put("artifact_sha256", "c".repeat(64))
            })
            assertEquals(null, localWorkDeliveryEvidence(log, goal).artifactCallId)
            log.append("tool/call", buildJsonObject {
                put("id", "current-call"); put("name", "write")
            })
            log.append("tool/result", buildJsonObject {
                put("id", "current-call"); put("name", "write"); put("is_error", false)
                put("artifact_path", "src/new.kt"); put("artifact_sha256", "d".repeat(64))
            })
            assertEquals("current-call", localWorkDeliveryEvidence(log, goal).artifactCallId)
            log.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun modelClaimCannotFabricatePositiveToolEvents() {
        val dir = Files.createTempDirectory("goal-evidence-model").toFile()
        try {
            val log = LocalSessionEventLog(File(dir, "events.jsonl"), Json)
            log.append("assistant/message", buildJsonObject {
                put("content", "代码修改完成，BUILD SUCCESSFUL，文件 sha256=abc123")
            })
            val proof = localWorkDeliveryEvidence(log, "修改应用代码并完成构建")
            assertEquals(2, proof.missing.size)
            log.close()
        } finally { dir.deleteRecursively() }
    }
}

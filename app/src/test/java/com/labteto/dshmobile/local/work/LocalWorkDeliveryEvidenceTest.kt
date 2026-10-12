package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkDeliveryEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun scenario(artifacts: List<String> = listOf("src/main.kt"), commands: List<String> = listOf("go test ./..."),
        body: (LocalSessionEventLog, LocalGoal, File) -> Unit) {
        val root = temporary.newFolder()
        val log = LocalSessionEventLog(File(root, "events.jsonl"), Json)
        val initial = LocalGoal("Implement app component and run checks", id = "goal-1", artifactPaths = artifacts, verificationCommands = commands)
        val goal = initial.copy(startedAtSequence = log.append("goal/state", workGoalEvent(initial)).sequence)
        try { body(log, goal, root) } finally { log.close() }
    }
    private fun result(log: LocalSessionEventLog, goal: LocalGoal, root: File, id: String, command: String = "go test ./...",
        exit: Int? = 0, error: Boolean = false, text: String = "", invoke: Boolean = true) {
        if (invoke) log.append("tool/call", buildJsonObject {
            put("id", id); put("goal_id", goal.id); put("name", "bash")
            put("arguments", buildJsonObject { put("command", command) })
        })
        log.append("tool/result", buildJsonObject {
            put("id", id); put("name", "bash"); put("is_error", error); put("content", text)
            exit?.let { put("process_exit_code", it) }
            put("file_versions", JsonObject(goal.artifactPaths.mapNotNull { path ->
                localWorkFileSha256(root, path)?.let { path to JsonPrimitive(it) }
            }.toMap()))
        })
    }
    private fun artifact(root: File, text: String = "actual source") = File(root, "src/main.kt").apply { parentFile.mkdirs(); writeText(text) }

    @Test fun completedTodoWithoutRealArtifactIsNotEnoughForCodeGoal() = scenario { log, goal, root ->
        assertEquals(2, localWorkDeliveryEvidence(log, goal, root).missing.size)
        val state = LocalWorkState(goal = goal, todos = listOf(LocalTodoItem("implement", "completed")))
        assertFalse(resolveWorkGoalTransition(goal, goal.copy(status = "completed"), state, localWorkDeliveryEvidence(log, goal, root)).accepted)
        artifact(root)
        result(log, goal, root, "write", command = "generate source", exit = null)
        val onlyFile = localWorkDeliveryEvidence(log, goal, root)
        assertEquals("write", onlyFile.artifactCallId)
        assertNull(onlyFile.testCallId)
        result(log, goal, root, "test")
        val verified = localWorkDeliveryEvidence(log, goal, root)
        assertTrue(verified.missing.isEmpty())
        assertTrue(resolveWorkGoalTransition(goal, goal.copy(status = "completed"), state, verified).accepted)
    }
    @Test fun successfulGoTestDoesNotNeedPrintedSuccessKeywords() = scenario { log, goal, root ->
        artifact(root); result(log, goal, root, "go-test", text = "ok example/app 0.4s")
        assertEquals("go-test", localWorkDeliveryEvidence(log, goal, root).testCallId)
    }
    @Test fun customVerifierAndEnglishGoalUseSameExplicitContract() = scenario(commands = listOf("python scripts/acceptance.py")) { log, goal, root ->
        artifact(root); result(log, goal, root, "custom", command = "python scripts/acceptance.py")
        assertTrue(localWorkDeliveryEvidence(log, goal, root).missing.isEmpty())
    }
    @Test fun newerCatOfOldBuildLogDoesNotMaskTheActualVerifier() = scenario { log, goal, root ->
        artifact(root); result(log, goal, root, "real")
        result(log, goal, root, "cat", command = "cat old-build.log", text = "BUILD SUCCESSFUL")
        assertEquals("real", localWorkDeliveryEvidence(log, goal, root).testCallId)
    }
    @Test fun latestFailureInvalidatesEarlierSuccessEvenIfStdoutClaimsSuccess() = scenario { log, goal, root ->
        artifact(root); result(log, goal, root, "success")
        result(log, goal, root, "failure", exit = 1, error = true, text = "BUILD SUCCESSFUL")
        assertNull(localWorkDeliveryEvidence(log, goal, root).testCallId)
    }
    @Test fun latestTimeoutWithoutAnExitCodeAlsoInvalidatesEarlierSuccess() = scenario { log, goal, root ->
        artifact(root); result(log, goal, root, "success")
        result(log, goal, root, "timeout", exit = null, error = true)
        assertNull(localWorkDeliveryEvidence(log, goal, root).testCallId)
    }
    @Test fun sourceChangeOrDeletionMakesEarlierVerificationStale() = scenario { log, goal, root ->
        val file = artifact(root); result(log, goal, root, "success")
        file.writeText("changed after verification")
        assertFalse(localWorkDeliveryEvidence(log, goal, root).missing.isEmpty())
        file.delete()
        assertNull(localWorkDeliveryEvidence(log, goal, root).artifactCallId)
    }
    @Test fun pauseAndResumeDoNotChangeTheGoalIdentityOrEvidenceHorizon() = scenario { log, goal, root ->
        artifact(root); result(log, goal, root, "success")
        log.append("goal/state", workGoalEvent(goal.copy(status = "paused")))
        log.append("goal/state", workGoalEvent(goal.copy(status = "active")))
        assertEquals("success", localWorkDeliveryEvidence(log, goal, root).testCallId)
        val replayed = projectWorkSessionControls(LocalWorkState(), log.snapshot()).goal!!
        assertEquals(goal.id, replayed.id)
        assertEquals(goal.startedAtSequence, replayed.startedAtSequence)
        assertTrue(localWorkDeliveryEvidence(log, replayed, root).missing.isEmpty())
    }
    @Test fun artifactOutputWithoutMatchingInvocationInCurrentGoalCannotPassAcceptance() = scenario { log, goal, root ->
        artifact(root)
        result(log, goal, root, "orphan", invoke = false)
        assertNull(localWorkDeliveryEvidence(log, goal, root).artifactCallId)
        result(log, goal.copy(id = "other-goal"), root, "wrong-owner")
        assertNull(localWorkDeliveryEvidence(log, goal, root).artifactCallId)
        result(log, goal, root, "current")
        assertEquals("current", localWorkDeliveryEvidence(log, goal, root).artifactCallId)
    }
    @Test fun invocationStartedBeforeNewGoalCannotBecomeItsLateEvidence() = scenario { log, goal, root ->
        artifact(root); result(log, goal, root, "old")
        val next = goal.copy(id = "goal-2", startedAtSequence = log.append("goal/state", workGoalEvent(goal.copy(id = "goal-2"))).sequence)
        result(log, next, root, "old", invoke = false)
        assertNull(localWorkDeliveryEvidence(log, next, root).testCallId)
    }
    @Test fun allDeclaredCommandsAndArtifactsMustBeSatisfied() = scenario(commands = listOf("go test ./...", "go vet ./...")) { log, goal, root ->
        artifact(root); result(log, goal, root, "test")
        assertNull(localWorkDeliveryEvidence(log, goal, root).testCallId)
        result(log, goal, root, "vet", command = "go vet ./...")
        assertTrue(localWorkDeliveryEvidence(log, goal, root).missing.isEmpty())
    }
    @Test fun modelClaimCannotFabricatePositiveToolEvents() = scenario { log, goal, root ->
        log.append("assistant/message", buildJsonObject { put("content", "All done. BUILD SUCCESSFUL.") })
        assertEquals(2, localWorkDeliveryEvidence(log, goal, root).missing.size)
    }
    @Test fun discussionGoalDoesNotAcquireUnrelatedFileOrTestObligations() = scenario(emptyList(), emptyList()) { log, goal, root ->
        assertTrue(localWorkDeliveryEvidence(log, goal.copy(description = "讨论应用代码的构建方案"), root).missing.isEmpty())
    }
}

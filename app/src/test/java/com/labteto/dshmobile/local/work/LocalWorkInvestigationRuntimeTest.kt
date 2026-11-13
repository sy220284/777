package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkInvestigationRuntimeTest {
    @get:Rule val files = TemporaryFolder()

    private fun log() = LocalSessionEventLog(
        File(files.root, "investigation-${System.nanoTime()}.events.jsonl"),
        Json { ignoreUnknownKeys = true }, sessionId = "investigation-test",
    )

    private fun observe(
        log: LocalSessionEventLog, id: String, name: String, source: String,
        range: Int, content: String, goal: String = "goal-a", message: String = "turn-a",
    ) {
        val call = log.append("tool/call", buildJsonObject {
            put("id", id); put("name", name); put("goal_id", goal)
        })
        val result = log.append("tool/result", buildJsonObject {
            put("id", id); put("is_error", false)
            put("tool_declared_sequence", call.sequence)
        })
        LocalWorkInvestigationRuntime.observe(
            log, goal, message, id, name,
            buildJsonObject { put("path", source); put("start_line", range); put("end_line", range + 9) },
            call.sequence, result.sequence, content, false,
        )
    }

    @Test fun recordsActualSourceSlicesAndDoesNotCountRepeatedFetchAsProgress() {
        val log = log()
        try {
            observe(log, "read-1", "read", "src/Agent.kt", 1,
                "1..10; next_start_line=11")
            val progress = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a")
            assertTrue(progress.isNotBlank())
            val context = LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
            assertTrue(context.contains("src/Agent.kt"))
            assertTrue(context.contains("lines:1-10"))
            assertTrue(context.contains("继续读取的游标"))
            assertTrue(context.contains("read-1"))
            assertFalse(context.contains("1..10; next_start_line=11"))
            val coverage = log.eventsOfTypesAfter(-1L, setOf("work/source-observed"))
            assertEquals(1, coverage.size)
            assertFalse(coverage.single().data.toString().contains("1..10; next_start_line=11"))
            observe(log, "read-again", "read", "src/Agent.kt", 1,
                "1..10; next_start_line=11")
            assertEquals(progress, LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
            observe(log, "read-2", "read", "src/Agent.kt", 11,
                "11..20; next_start_line=21")
            assertFalse(progress == LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
        } finally { log.close() }
    }

    @Test fun searchOnlyIsNotMistakenForSourceVerificationOrAnotherTurn() {
        val log = log()
        try {
            observe(log, "search-a", "grep", ".", 1, "match at src/Agent.kt",
                goal = "", message = "turn-a")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "", "turn-a")
                .contains("仅定位线索"))
            assertTrue(LocalWorkInvestigationRuntime.context(log, "", "turn-b").isEmpty())
            observe(log, "search-b", "grep", ".", 1, "different",
                goal = "", message = "turn-b")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "", "turn-a")
                .contains("search-a"))
            assertFalse(LocalWorkInvestigationRuntime.context(log, "", "turn-a")
                .contains("search-b"))
        } finally { log.close() }
    }

    @Test fun activeFindingUpdatesRequestContextAndItsLatestStateChangesProgressIdentity() {
        val log = log()
        try {
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a")
                put("finding_id", "finding-1")
                put("status", "hypothesis")
                put("summary", "怀疑公共组件重复实现")
                put("next_action", "检查调用链")
            })
            val initial = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("检查调用链"))
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a")
                put("finding_id", "finding-1")
                put("status", "rejected")
                put("summary", "经过检查，该假说不成立")
            })
            assertFalse(initial == LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
            assertFalse(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("怀疑公共组件重复实现"))
        } finally { log.close() }
    }

    @Test fun recoversOnlyASettledReadFailureFollowedBySuccessfulRead() {
        val log = log()
        try {
            log.append("turn/start", buildJsonObject { put("run_id", "r1") })
            log.append("tool/result", buildJsonObject {
                put("id", "one"); put("name", "read")
                put("is_error", true); put("side_effect", "none")
            })
            assertFalse(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("tool/result", buildJsonObject {
                put("id", "two"); put("name", "read")
                put("is_error", false); put("side_effect", "none")
            })
            assertTrue(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("tool/result", buildJsonObject {
                put("id", "three"); put("name", "write")
                put("is_error", true); put("side_effect", "possible")
            })
            assertFalse(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
        } finally { log.close() }
    }

    @Test fun unknownSourceResultAndFailedReadDoNotCreateEvidence() {
        val log = log()
        try {
            LocalWorkInvestigationRuntime.observe(log, "goal-a", "turn-a", "no-declaration",
                "read", buildJsonObject { put("path", "src/A.kt") },
                null, 10L, "data", false)
            LocalWorkInvestigationRuntime.observe(log, "goal-a", "turn-a", "failed",
                "read", buildJsonObject { put("path", "src/A.kt") },
                1L, 2L, "error", true)
            assertTrue(log.eventsOfTypesAfter(-1L, setOf("work/source-observed")).isEmpty())
            assertTrue(LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a").isEmpty())
        } finally { log.close() }
    }
}

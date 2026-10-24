package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.work.LocalGoal
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorkGoalEvidenceScopeTest {
    @Test fun laterGoalsAndTheirFailuresNeverInflateEarlierGoalEvidence() {
        val directory = Files.createTempDirectory("work-goal-evidence").toFile()
        try {
            val log = LocalSessionEventLogRegistry(directory, Json).get("scoped")
            log.append("goal/state", buildJsonObject {
                put("id", "a"); put("status", "active"); put("revision", 1)
            })
            val aStart = log.latestSequence()
            log.append("tool/result", buildJsonObject { put("is_error", false) })
            log.append("goal/state", buildJsonObject {
                put("id", "a"); put("status", "completed"); put("revision", 2)
                put("startedAtSequence", aStart)
            })
            log.append("goal/state", buildJsonObject {
                put("id", "b"); put("status", "active"); put("revision", 1)
            })
            val bStart = log.latestSequence()
            log.append("tool/result", buildJsonObject {
                put("is_error", true); put("error_code", "INVALID_TOOL_ARGUMENTS")
            })
            log.append("goal/state", buildJsonObject {
                put("id", "b"); put("status", "completed"); put("revision", 2)
                put("startedAtSequence", bStart)
            })
            val a = LocalGoal("任务 A", status = "completed", id = "a",
                startedAtSequence = aStart, revision = 2)
            val b = LocalGoal("任务 B", status = "completed", id = "b",
                startedAtSequence = bStart, revision = 2)
            assertEquals(1 to 0, workGoalToolMetrics(log, a))
            assertEquals(1 to 1, workGoalToolMetrics(log, b))
            assertEquals(0, WorkExperienceBootstrap.collect(log, a).toolFailures)
            assertEquals(1, WorkExperienceBootstrap.collect(log, b).toolContractErrors)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun switchingToAnotherGoalAndBackDoesNotCountOtherGoalsTools() {
        val directory = Files.createTempDirectory("work-goal-switch").toFile()
        try {
            val log = LocalSessionEventLogRegistry(directory, Json).get("switch")
            log.append("goal/state", buildJsonObject {
                put("id", "a"); put("status", "active"); put("revision", 1)
            })
            val start = log.latestSequence()
            log.append("tool/result", buildJsonObject { put("is_error", false) })
            log.append("goal/state", buildJsonObject {
                put("id", "b"); put("status", "active"); put("revision", 1)
            })
            log.append("tool/result", buildJsonObject { put("is_error", true) })
            log.append("goal/state", buildJsonObject {
                put("id", "a"); put("status", "active"); put("revision", 2)
                put("startedAtSequence", start)
            })
            log.append("tool/result", buildJsonObject { put("is_error", false) })
            log.append("goal/state", buildJsonObject {
                put("id", "a"); put("status", "completed"); put("revision", 3)
                put("startedAtSequence", start)
            })
            val goal = LocalGoal("任务 A", status = "completed", id = "a",
                startedAtSequence = start, revision = 3)
            assertEquals(2 to 0, workGoalToolMetrics(log, goal))
        } finally {
            directory.deleteRecursively()
        }
    }
}

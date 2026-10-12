package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAdmissionRequest
import com.labteto.dshmobile.local.model.LocalPromptPressure
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.model.LocalRequestPressureStore
import com.labteto.dshmobile.local.model.validateModelRequestAdmission
import com.labteto.dshmobile.local.work.assessWorkStepContext
import com.labteto.dshmobile.local.work.toRequestContextAssessment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptPressureTest {
    @Test
    fun bucketsAreMutuallyExclusiveAndSumToEstimatedInput() {
        val messages = listOf(
            buildJsonObject { put("role", "system"); put("content", "固定规则") },
            buildJsonObject { put("role", "user"); put("content", "旧问题") },
            buildJsonObject { put("role", "assistant"); put("content", "旧回复") },
            buildJsonObject { put("role", "user"); put("content", "当前问题") },
        )
        val tools = JsonArray(emptyList())

        val pressure = LocalPromptPressureMeter.measure(messages, tools, 100_000)

        assertEquals(
            pressure.estimatedInputTokens,
            pressure.systemTokens + pressure.historyTokens +
                pressure.currentUserTokens + pressure.toolDefinitionTokens,
        )
        assertTrue(pressure.currentUserTokens > 0)
        assertTrue(pressure.historyTokens > 0)
    }

    @Test
    fun genericModelAdmissionRejectsOversizedRequestWithoutWorkControl() {
        val request = LocalModelAdmissionRequest(
            routeFingerprint = "test-route",
            model = "unknown-model",
            baseUrl = "https://example.com/v1",
            contextWindowTokensOverride = 4_096,
            messages = listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", "x".repeat(100_000))
                },
            ),
            tools = JsonArray(emptyList()),
        )

        val failure = runCatching { validateModelRequestAdmission(request) }.exceptionOrNull()

        assertTrue(failure is LocalModelException)
        assertEquals("MODEL_CONTEXT_BUDGET_EXCEEDED", (failure as LocalModelException).code)
    }

    @Test
    fun unknownModelOperationalLimitIsARealGuard() {
        val limit = operationalInputLimitTokens("unknown-model", "https://example.com/v1")
        assertTrue(limit in 1..300_000)
    }

    @Test
    fun pressureStoreKeepsLatestWorkAssessmentAlongsidePressure() {
        val store = LocalRequestPressureStore()
        val pressure = LocalPromptPressure(
            contextChars = 100,
            estimatedInputTokens = 30_000,
            systemTokens = 3_000,
            historyTokens = 22_000,
            currentUserTokens = 2_000,
            toolDefinitionTokens = 3_000,
            operationalLimitTokens = 678_464,
        )
        val assessment = assessWorkStepContext(
            current = pressure,
            previous = null,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
        )
        val assessmentSnapshot = assessment.toRequestContextAssessment()

        val sourcePressure = pressure.copy(
            estimatedInputTokens = 42_000,
            historyTokens = 34_000,
        )
        store.record(
            sessionId = "work",
            pressure = pressure,
            contextAssessment = assessmentSnapshot,
            usageMode = LocalUsageMode.WORK,
            sourcePressure = sourcePressure,
        )
        val laterChatPressure = pressure.copy(
            estimatedInputTokens = 9_000,
            historyTokens = 3_000,
            currentUserTokens = 2_000,
        )
        store.record("work", laterChatPressure)

        assertEquals(laterChatPressure, store.latest("work"))
        assertEquals(pressure, store.latestWork("work"))
        assertEquals(sourcePressure, store.latestWorkSource("work"))
        assertEquals(assessmentSnapshot, store.workAssessment("work"))
    }

    @Test
    fun pressureStoreBoundsSessionsAndResetsGenerationPrefillAfterCompaction() {
        val store = LocalRequestPressureStore(maxSessions = 2)
        val pressure = LocalPromptPressure(
            contextChars = 100,
            estimatedInputTokens = 50,
            systemTokens = 10,
            historyTokens = 20,
            currentUserTokens = 20,
            toolDefinitionTokens = 0,
            operationalLimitTokens = 1_000,
        )

        store.record("a", pressure)
        store.recordReportedUsage("a", 48)
        assertEquals("server", store.window("a")?.prefillSource)
        assertEquals(48L, store.window("a")?.prefillTokens)

        store.advanceGeneration("a", 24)
        assertEquals(2, store.window("a")?.generation)
        assertEquals("estimated", store.window("a")?.prefillSource)
        assertEquals(24L, store.window("a")?.prefillTokens)

        store.record("b", pressure)
        store.record("c", pressure)
        assertEquals(2, store.trackedSessionCount())
        assertEquals(null, store.window("a"))
    }

    @Test
    fun routeCalibrationNeedsFourRealUsageSamplesAndNeverChangesHardAdmission() {
        val store = LocalRequestPressureStore()
        val pressure = LocalPromptPressure(
            contextChars = 80_000,
            estimatedInputTokens = 40_000,
            systemTokens = 4_000,
            historyTokens = 30_000,
            currentUserTokens = 2_000,
            toolDefinitionTokens = 4_000,
            operationalLimitTokens = 45_000,
        )
        repeat(3) { store.recordReportedUsage("s", 30_000, "route-a", 40_000) }
        assertEquals(pressure, store.forSoftProjection("route-a", pressure))
        store.recordReportedUsage("s", 30_000, "route-a", 40_000)
        assertEquals(30_000, store.forSoftProjection("route-a", pressure).estimatedInputTokens)
        assertEquals(1.0, store.softProjectionScale("route-b"), 0.00001)
        assertEquals(pressure, store.forSoftProjection("route-b", pressure))
        assertEquals(pressure.operationalLimitTokens, store.forSoftProjection("route-a", pressure).operationalLimitTokens)

        val request = LocalModelAdmissionRequest(
            routeFingerprint = "route-a",
            model = "custom-model",
            baseUrl = "https://proxy.example/v1",
            contextWindowTokensOverride = null,
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "x".repeat(25_000))
            }),
            tools = JsonArray(emptyList()),
            operationalInputLimitSnapshot = 1_000,
        )
        val hardFailure = runCatching { validateModelRequestAdmission(request) }.exceptionOrNull()
        assertTrue(hardFailure is LocalModelException)
        assertEquals("MODEL_CONTEXT_BUDGET_EXCEEDED", (hardFailure as LocalModelException).code)
    }

    @Test
    fun reportedUsageCalibrationIsBoundedAndDoesNotApplyToMissingUsage() {
        val store = LocalRequestPressureStore()
        store.recordReportedUsage("s", 0, "route-a", 10_000)
        assertEquals(null, store.routeCalibration("route-a"))
        repeat(5) { store.recordReportedUsage("s", 3, "route-a", 50_000) }
        assertEquals(0.70, store.softProjectionScale("route-a"), 0.00001)
        repeat(6) { store.recordReportedUsage("s", 500_000, "route-b", 50_000) }
        assertEquals(1.15, store.softProjectionScale("route-b"), 0.00001)
    }


}

package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.agent.LocalAgentModelStepRecovery
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRecoveryPolicy
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRuntime
import com.labteto.dshmobile.local.automation.AutomationPlanningRevision
import com.labteto.dshmobile.local.automation.resolveAutomationPlanningRevision
import com.labteto.dshmobile.local.quality.LocalOutputQualityContext
import com.labteto.dshmobile.local.quality.LocalOutputQualityGuard
import com.labteto.dshmobile.local.quality.LocalOutputQualityPipeline
import com.labteto.dshmobile.local.quality.LocalOutputQualityResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentRuntimeFoundationV3Test {
    @Test
    fun modelStepRuntimeOwnsRetryAndRecoveryLifecycle() = runTest {
        val runtime = LocalAgentModelStepRuntime()
        var calls = 0
        var recoveries = 0
        val initial = listOf(buildJsonObject {
            put("role", "user")
            put("content", "原始请求")
        })

        val result = runtime.execute(
            initialMessages = initial,
            maxAttempts = 2,
            retryable = { it.message == "retry" },
            backoffMillis = { _, _ -> 0L },
            recoveryPolicy = LocalAgentModelStepRecoveryPolicy { error, active, _ ->
                if (error.message != "recover") {
                    null
                } else {
                    recoveries += 1
                    LocalAgentModelStepRecovery(
                        messages = active + buildJsonObject {
                            put("role", "user")
                            put("content", "恢复后继续")
                        },
                        reason = "test",
                    )
                }
            },
        ) { active ->
            calls += 1
            when (calls) {
                1 -> throw IllegalStateException("retry")
                2 -> throw IllegalStateException("recover")
                else -> active.size
            }
        }

        assertEquals(3, calls)
        assertEquals(1, recoveries)
        assertEquals(2, result)
    }

    @Test
    fun automationRevisionRejectsStaleCandidateInsideSameSession() {
        val current = AutomationPlanningRevision(
            sessionId = "session-a",
            latestDialogueMessageId = "a2",
            chatContextGeneration = 8L,
        )
        val exact = resolveAutomationPlanningRevision(current, current)
        val stale = resolveAutomationPlanningRevision(
            current,
            current.copy(latestDialogueMessageId = "a1", chatContextGeneration = 7L),
        )

        assertTrue(exact.accepted)
        assertFalse(stale.accepted)
        assertEquals(current, stale.value)
    }

    @Test
    fun qualityPipelineRunsDomainGuardsInOrder() {
        val first = LocalOutputQualityGuard { text, _ ->
            LocalOutputQualityResult(
                text = text + "A",
                findings = listOf("first"),
                changed = true,
            )
        }
        val second = LocalOutputQualityGuard { text, _ ->
            LocalOutputQualityResult(
                text = text + "B",
                findings = listOf("second"),
                changed = true,
            )
        }

        val result = LocalOutputQualityPipeline(listOf(first, second))
            .inspect("start", LocalOutputQualityContext())

        assertEquals("startAB", result.text)
        assertEquals(listOf("first", "second"), result.findings)
        assertTrue(result.changed)
    }
}

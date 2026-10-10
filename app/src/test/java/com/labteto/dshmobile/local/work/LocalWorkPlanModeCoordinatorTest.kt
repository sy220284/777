package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.workSystemPrompt
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkPlanModeCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun regeneratingAPlanContinuesTheSameWorkQuestionWithoutApprovingOrStartingNewTask() = runTest {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(
            sessionId = "plan-rewrite", loading = false, usageMode = LocalUsageMode.WORK,
        ))
        val logs = LocalSessionEventLogRegistry(
            File(temporary.root, "plan-regenerate").apply { mkdirs() }, json,
        )
        var work = LocalWorkState(planMode = true)
        val port = object : LocalWorkStatePort {
            override fun snapshot(): LocalWorkState = work
            override fun update(transform: (LocalWorkState) -> LocalWorkState) {
                work = transform(work)
            }
        }
        val call = LocalToolCall("replan-call", "exit_plan_mode", buildJsonObject { }, "{}")
        val awaiting = async {
            exitWorkPlanMode(
                call = call,
                plan = "第一版方案",
                state = port,
                interactions = runtime.foregroundInteractions,
                aggregateSnapshot = { runtime.state.value },
                history = LocalModelHistoryBuffer(),
                eventLog = logs.get("plan-rewrite"),
                persist = { error("重新规划不得提交已批准计划") },
                updateContextMetrics = { error("重新规划不得改变执行模式") },
            )
        }
        runCurrent()
        val question = requireNotNull(runtime.state.value.work.pendingQuestion)
        assertEquals("replan-call", question.callId)
        assertTrue(question.options.contains("重新生成方案"))
        assertTrue(runtime.foregroundInteractions.answerQuestion(question.callId, "重新生成方案"))
        assertTrue(awaiting.await().contains("再次调用 exit_plan_mode"))
        assertTrue(work.planMode)
        assertEquals(null, logs.get("plan-rewrite").latest("plan/approved"))
        logs.clearAndEvict(setOf("plan-rewrite"))
    }

    @Test
    fun planModeCommitsInWorkBoundaryAndUpdatesForegroundPrompt() {
        val fixture = fixture()
        fixture.runtime.foregroundRunHandle.modelHistory.prepend(buildJsonObject {
            put("role", "system")
            put("content", workSystemPrompt("/workspace", false))
        })

        assertTrue(fixture.coordinator.setEnabled(true))
        assertTrue(fixture.runtime.state.value.work.planMode)
        assertTrue(
            fixture.logs.get("work").latest("plan/mode")
                ?.data?.get("active")?.jsonPrimitive?.booleanOrNull == true,
        )
        val promptText = fixture.runtime.foregroundRunHandle.modelHistory.snapshot()
            .mapNotNull { it["content"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
        assertTrue(promptText.contains("规划模式"))
        assertTrue(fixture.runtime.state.value.kernel.contextChars > 0)

        fixture.logs.clearAndEvict(setOf("work"))
    }

    @Test
    fun currentSessionOwnerBlocksPlanModeButUnrelatedSessionDoesNot() {
        val fixture = fixture()
        val currentLease = requireNotNull(
            LocalSessionRuntimeRegistry.tryAcquire("work", LocalSessionRuntimeKind.FOREGROUND),
        )
        try {
            assertFalse(fixture.coordinator.setEnabled(true))
        } finally {
            currentLease.close()
        }

        val unrelatedLease = requireNotNull(
            LocalSessionRuntimeRegistry.tryAcquire("other", LocalSessionRuntimeKind.FOREGROUND),
        )
        try {
            assertTrue(fixture.coordinator.setEnabled(true))
        } finally {
            unrelatedLease.close()
        }
        fixture.logs.clearAndEvict(setOf("work"))
    }

    @Test
    fun chatOrLoadingStateCannotEnterWorkPlanMode() {
        val runtime = LocalRuntimeStateStore()
        val ownerState = runtime.initialize(
            LocalHarnessState(
                loading = false,
                sessionId = "chat",
                usageMode = LocalUsageMode.CHAT,
                workspacePath = "/workspace",
            ),
        )
        val registry = LocalWorkRunRegistry(runtime)
        val logs = LocalSessionEventLogRegistry(
            sessionsRoot = File(temporary.root, "blocked").apply { mkdirs() },
            json = json,
        )
        val coordinator = LocalWorkPlanModeCoordinator(runtime, registry, logs)

        assertFalse(coordinator.setEnabled(true))
        ownerState.value = runtime.state.value.copy(
            usageMode = LocalUsageMode.WORK,
            loading = true,
        )
        assertFalse(coordinator.setEnabled(true))
        assertFalse(runtime.state.value.work.planMode)
    }

    @Test
    fun failedEventWriteDoesNotPublishModeOrMutateModelHistoryAndReleasesOwner() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(
            loading = false, sessionId = "broken", usageMode = LocalUsageMode.WORK,
        ))
        runtime.foregroundRunHandle.modelHistory.prepend(buildJsonObject {
            put("role", "system")
            put("content", workSystemPrompt("/workspace", false))
        })
        val originalHistory = runtime.foregroundRunHandle.modelHistory.snapshot()
        val root = File(temporary.root, "blocked-log-root").apply { writeText("file, not directory") }
        val logs = LocalSessionEventLogRegistry(root, json)
        val coordinator = LocalWorkPlanModeCoordinator(runtime, LocalWorkRunRegistry(runtime), logs)

        val failure = runCatching { coordinator.setEnabled(true) }.exceptionOrNull()
        assertTrue(failure != null)
        assertFalse(runtime.state.value.work.planMode)
        org.junit.Assert.assertEquals(originalHistory, runtime.foregroundRunHandle.modelHistory.snapshot())
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("broken"))
    }

    @Test
    fun restoredPendingPlanReviewOrApprovalCannotReleaseReadOnlyMode() {
        val runtime = LocalRuntimeStateStore()
        val ownerState = runtime.initialize(LocalHarnessState(
            loading = false, sessionId = "pending-plan", usageMode = LocalUsageMode.WORK,
        ))
        val logs = LocalSessionEventLogRegistry(
            sessionsRoot = File(temporary.root, "pending-plan-logs").apply { mkdirs() },
            json = json,
        )
        val coordinator = LocalWorkPlanModeCoordinator(runtime, LocalWorkRunRegistry(runtime), logs)
        ownerState.value = ownerState.value.copy(work = LocalWorkState(
            planMode = true,
            pendingQuestion = LocalQuestion(
                callId = "plan-review", question = "Harness 已完成计划，是否批准并进入执行模式？",
            ),
        ))

        assertFalse(coordinator.setEnabled(false))
        assertTrue(runtime.state.value.work.planMode)
        assertTrue(logs.get("pending-plan").latest("plan/mode") == null)

        ownerState.value = ownerState.value.copy(work = LocalWorkState(
            planMode = true,
            pendingApproval = LocalApproval(
                callId = "tool-approval", toolName = "bash", summary = "执行",
                arguments = "{}", access = "process",
            ),
        ))
        assertFalse(coordinator.setEnabled(false))
        assertTrue(runtime.state.value.work.planMode)
        assertTrue(logs.get("pending-plan").latest("plan/mode") == null)
        logs.clearAndEvict(setOf("pending-plan"))
    }

    private fun fixture(): Fixture {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                loading = false,
                sessionId = "work",
                usageMode = LocalUsageMode.WORK,
                workspacePath = "/workspace",
            ),
        )
        val registry = LocalWorkRunRegistry(runtime)
        val logs = LocalSessionEventLogRegistry(
            sessionsRoot = File(temporary.root, "sessions").apply { mkdirs() },
            json = json,
        )
        return Fixture(runtime, logs, LocalWorkPlanModeCoordinator(runtime, registry, logs))
    }

    private data class Fixture(
        val runtime: LocalRuntimeStateStore,
        val logs: LocalSessionEventLogRegistry,
        val coordinator: LocalWorkPlanModeCoordinator,
    )
}

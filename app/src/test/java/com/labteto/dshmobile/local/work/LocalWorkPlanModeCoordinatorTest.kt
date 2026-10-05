package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.workSystemPrompt
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    fun planModeCommitsInWorkBoundaryAndUpdatesForegroundPrompt() {
        val fixture = fixture()
        fixture.runtime.foregroundModelHistory.prepend(buildJsonObject {
            put("role", "system")
            put("content", workSystemPrompt("/workspace", false))
        })

        assertTrue(fixture.coordinator.setEnabled(true))
        assertTrue(fixture.runtime.state.value.work.planMode)
        assertTrue(
            fixture.logs.get("work").latest("plan/mode")
                ?.data?.get("active")?.jsonPrimitive?.booleanOrNull == true,
        )
        val promptText = fixture.runtime.foregroundModelHistory.snapshot()
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
        runtime.initialize(
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
        runtime.mutableState.value = runtime.state.value.copy(
            usageMode = LocalUsageMode.WORK,
            loading = true,
        )
        assertFalse(coordinator.setEnabled(true))
        assertFalse(runtime.state.value.work.planMode)
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

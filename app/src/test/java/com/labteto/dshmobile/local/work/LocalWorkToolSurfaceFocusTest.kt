package com.labteto.dshmobile.local.work

import android.content.SharedPreferences
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalToolExecutionCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Full Work model-schema path: a prior capability_search(select:...) must survive
 * subsequent pinned-MCP/team/archive automatic discovery until a new user task.
 */
class LocalWorkToolSurfaceFocusTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun explicitFocusSurvivesModelStepsAndRestoresDefaultsOnNewUserTask() = runBlocking {
        val registry = ToolRegistry().apply {
            register(optional("mcp_pinned_status"))
            register(optional("mcp_selected_lookup"))
        }
        val executor = LocalToolExecutionCoordinator(
            registry = registry,
            currentSessionId = { "focus-session" },
            planMode = { false },
            requestApproval = { _, _, _ -> true },
            pinnedConnectorIds = { listOf("pinned") },
        )
        val runtime = LocalWorkTurnToolRuntime(
            registry = registry,
            execution = executor,
            schemas = LocalToolSchemaProjection(registry, executor),
            approvalPreferences = approvals(),
            githubConfigured = { false },
        )
        val log = LocalSessionEventLog(File(files.newFolder(), "events.jsonl"), Json)
        val binding = LocalWorkRunBinding(
            sessionId = "focus-session",
            initialState = LocalHarnessState(
                sessionId = "focus-session", usageMode = LocalUsageMode.WORK,
            ).toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(id = "focus-session", usageMode = LocalUsageMode.WORK),
            runHandle = LocalAgentRunHandle(initialSessionId = "focus-session", maxPendingInputs = 8),
            eventLog = log,
        )
        try {
            binding.runHandle.modelHistory.append(buildJsonObject {
                put("role", "user")
                put("content", "请查询相关信息")
            })
            val policy = localAgentRunPolicy(LocalUsageMode.WORK)
            runtime.prepare(binding, "请查询相关信息")
            val firstNames = LocalToolSchemaProjection(registry, executor).names(
                runtime.schemas(policy, binding),
            )
            assertTrue("mcp_pinned_status" in firstNames)

            executor.searchCapabilities("select:mcp_selected_lookup", binding.enabledOptionalTools)
            assertTrue(binding.enabledOptionalTools.exactFocus)
            val secondNames = LocalToolSchemaProjection(registry, executor).names(
                runtime.schemas(policy, binding),
            )
            assertTrue("mcp_selected_lookup" in secondNames)
            assertFalse("mcp_pinned_status" in secondNames)
            assertFalse("mcp_pinned_status" in binding.enabledOptionalTools)

            // User supplies a genuinely new task: the existing priority cursor releases
            // the old exact focus, and the pinned tool is eligible again.
            binding.runHandle.modelHistory.append(buildJsonObject {
                put("role", "user")
                put("content", "现在换一个任务：查询 pinned 服务的状态")
            })
            val changedNames = LocalToolSchemaProjection(registry, executor).names(
                runtime.schemas(policy, binding),
            )
            assertFalse(binding.enabledOptionalTools.exactFocus)
            assertTrue("mcp_pinned_status" in changedNames)
        } finally {
            log.close()
        }
    }

    private fun optional(name: String) = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "Read data"),
        access = ToolAccess.READ_ONLY,
        approvalPolicy = ToolApprovalPolicy.NEVER,
        exposure = ToolExposure.OPTIONAL,
        metadata = ToolMetadata(family = "MCP", discoveryKeywords = setOf("状态", "查询")),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

    private fun approvals(): LocalApprovalPreferences {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> null
                "contains" -> false
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        return LocalApprovalPreferences(preferences)
    }
}

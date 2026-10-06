package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalPersistentJobRecoveryCoordinator
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorkAgentControlBuiltinRuntimeTest {
    @Test
    fun unrelatedBuiltinIsLeftForSharedRouting() = runBlocking {
        val runtime = runtimeThatMustNotReachDependencies()

        assertNull(
            runtime.execute(
                LocalToolCall("call-1", "read", buildJsonObject {}, "{}"),
                allowMutation = false,
                binding = null,
            ),
        )
    }

    @Test
    fun agentControlBuiltinRejectsLegacyUnboundExecution() = runBlocking {
        val runtime = runtimeThatMustNotReachDependencies()

        assertEquals(
            "Work 代理工具缺少活动运行上下文，已拒绝旧的无绑定执行路径",
            runtime.execute(
                LocalToolCall("call-2", "workflow", buildJsonObject {}, "{}"),
                allowMutation = false,
                binding = null,
            ),
        )
    }

    private fun runtimeThatMustNotReachDependencies(): LocalWorkAgentControlBuiltinRuntime =
        LocalWorkAgentControlBuiltinRuntime(
            jobs = errorDependency(),
            modelGateway = errorDependency(),
            subagents = errorDependency(),
            persistentJobs = errorDependency(),
        )

    private fun <T> errorDependency(): T =
        throw AssertionError("无绑定/非 Work 路径不得解析代理控制依赖")
}

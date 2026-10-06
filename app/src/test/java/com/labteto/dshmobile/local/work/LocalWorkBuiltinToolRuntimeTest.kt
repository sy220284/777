package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorkBuiltinToolRuntimeTest {
    private val runtime = LocalWorkBuiltinToolRuntime(
        persist = { error("无绑定路径不得持久化") },
        updateContextMetrics = { error("无绑定路径不得更新上下文指标") },
    )

    @Test
    fun unrelatedBuiltinIsLeftForSharedRouting() = runBlocking {
        val result = runtime.execute(
            LocalToolCall("call-1", "read", buildJsonObject {}, "{}"),
            binding = null,
        )

        assertNull(result)
    }

    @Test
    fun workTaskBuiltinRejectsLegacyUnboundExecution() = runBlocking {
        val result = runtime.execute(
            LocalToolCall(
                "call-2",
                "update_plan",
                buildJsonObject {},
                "{}",
            ),
            binding = null,
        )

        assertEquals(
            "Work 工具缺少活动运行上下文，已拒绝旧的无绑定执行路径",
            result,
        )
    }
}

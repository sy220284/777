package com.labteto.dshmobile.local.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.agent.AgentBackendRequest
import com.labteto.dshmobile.harness.agent.AgentBackendServices
import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentStopReason
import com.labteto.dshmobile.harness.agent.AgentToolBatchExecutor
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.agent.ipc.AndroidIsolatedAgentBackend
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedAgentBackendAndroidTest {
    @Test fun independentUidCannotReadHostPrivateFileAndUsesHostToolReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val events = mutableListOf<AgentEvent>()
        var step = 0; var effects = 0
        val call = AgentToolCall("call-1", "write", buildJsonObject { })
        // Backend startup verifies actual distinct PID/UID and an EACCES private-file probe.
        val result = withTimeout(30_000) {
            AndroidIsolatedAgentBackend(context).run(AgentBackendRequest(UUID.randomUUID().toString(), "task", emptyList(), 3),
                AgentBackendServices(AgentModel { if (++step == 1) AgentModelReply(toolCalls = listOf(call)) else AgentModelReply("done") },
                    AgentToolExecutor { effects++; AgentToolResult("receipt", executionId = "host-effect") },
                    AgentToolBatchExecutor { error("unexpected batch") }, { false }, AgentEventSink { events += it }, null))
        }
        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertEquals(1, effects)
        assertEquals("host-effect", events.filterIsInstance<AgentEvent.ToolFinished>().single().executionId)
    }
    @Test fun cancellingOneActivationDoesNotKillAnotherIsolatedInstance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        fun services(model: AgentModel) = AgentBackendServices(model, AgentToolExecutor { error("no tools") },
            AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { }, null)
        withTimeout(30_000) {
            val first = async { AndroidIsolatedAgentBackend(context).run(AgentBackendRequest("first", "one", emptyList(), 1),
                services(AgentModel { firstEntered.complete(Unit); awaitCancellation() })) }
            val second = async { AndroidIsolatedAgentBackend(context).run(AgentBackendRequest("second", "two", emptyList(), 1),
                services(AgentModel { secondEntered.complete(Unit); releaseSecond.await(); AgentModelReply("survived") })) }
            try {
                firstEntered.await(); secondEntered.await(); first.cancelAndJoin()
                releaseSecond.complete(Unit)
                assertEquals("survived", second.await().answer)
                assertTrue(first.isCancelled)
            } finally { first.cancelAndJoin(); second.cancelAndJoin() }
        }
    }
}

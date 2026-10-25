package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolCancellationSettlementTest {
    private fun tool(finish: Boolean, execute: suspend () -> ToolResult) = HarnessTool(
        "commit_file", functionToolSchema("commit_file", "short atomic file commit"),
        ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS, ToolExposure.CORE,
        ToolMetadata("file", finishesOnCancellation = finish),
        executor = HarnessToolExecutor { _, _, _ -> execute() },
    )

    @Test
    fun startedCommitSettlesItsReceiptAndCancellationStillStopsTheNextAction() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val registry = ToolRegistry()
        registry.register(tool(true) { entered.complete(Unit); finish.await(); ToolResult("committed") })
        var receipt: String? = null
        var nextAction = false
        val caller = launch {
            registry.execute("commit_file", buildJsonObject {}, context = ToolContext(
                approval = { true }, onExecutionFinished = { _, result -> receipt = result.content },
            ))
            currentCoroutineContext().ensureActive()
            nextAction = true
        }
        entered.await()
        caller.cancel()
        finish.complete(Unit)
        caller.join()
        assertEquals("committed", receipt)
        assertFalse(nextAction)
        assertTrue(caller.isCancelled)
    }

    @Test
    fun approvalRemainsCancellableEvenForFinishOnCancellationHandlers() = runTest {
        val approvalStarted = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<Boolean>()
        var executed = false
        var receipted = false
        val registry = ToolRegistry()
        registry.register(tool(true) { executed = true; ToolResult("unexpected") })
        val caller = launch {
            registry.execute("commit_file", buildJsonObject {}, context = ToolContext(
                approval = { approvalStarted.complete(Unit); decision.await() },
                onExecutionFinished = { _, _ -> receipted = true },
            ))
        }
        approvalStarted.await()
        caller.cancel()
        caller.join()
        assertFalse(executed)
        assertFalse(receipted)
    }

    @Test
    fun ordinaryHandlersStillCooperateWithCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var receipted = false
        val registry = ToolRegistry()
        registry.register(tool(false) { entered.complete(Unit); finish.await(); ToolResult("unexpected") })
        val caller = launch {
            registry.execute("commit_file", buildJsonObject {}, context = ToolContext(
                approval = { true }, onExecutionFinished = { _, _ -> receipted = true },
            ))
        }
        entered.await()
        caller.cancel()
        caller.join()
        assertFalse(receipted)
    }
}

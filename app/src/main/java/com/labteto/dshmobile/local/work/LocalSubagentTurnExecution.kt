package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentRunResult
import com.labteto.dshmobile.harness.agent.AgentTurnEngine
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Resume exceptional turns here before restoring the Runner's large captured configuration.
 * Android's interpreter aborted resolving a spilled field with cancellation still pending.
 * The caller immediately unwraps the original exception; cancellation, leases and events
 * remain owned by the same activation and are never converted into a successful turn.
 */
internal suspend fun executeLocalSubagentTurn(
    engine: AgentTurnEngine,
    task: String,
    executionContext: CoroutineContext?,
): Result<AgentRunResult> = try {
    Result.success(if (executionContext == null) engine.run(task)
        else withContext(executionContext) { engine.run(task) })
} catch (failure: Exception) {
    Result.failure(failure)
}

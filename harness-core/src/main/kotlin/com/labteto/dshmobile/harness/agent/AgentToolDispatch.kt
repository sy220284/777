package com.labteto.dshmobile.harness.agent

/**
 * Owns tool batching and the model-visible settlement order for one step.
 * Actual admission, permission, resource leases and side-effect identity belong
 * to the injected Platform Tool Executor; ToolStarted is model declaration only.
 */
internal class AgentToolDispatch(
    private val tools: AgentToolExecutor,
    private val batch: AgentToolBatchExecutor,
    private val mayRunParallel: (AgentToolCall) -> Boolean,
    private val emit: AgentEventSink,
) {
    suspend fun executeStep(
        turn: AgentTurnState,
        step: Int,
        calls: List<AgentToolCall>,
    ) {
        var index = 0
        while (index < calls.size) {
            val first = calls[index]
            val parallel = mayRunParallel(first)
            val group = if (parallel) calls.drop(index).takeWhile(mayRunParallel)
                else listOf(first)

            // All call declarations are durably appended before dispatch begins.
            for (call in group) emit.append(AgentEvent.ToolStarted(turn.turnId, step, call))
            val results = if (parallel) {
                batch.execute(group)
            } else {
                listOf(tools.execute(first))
            }
            check(results.size == group.size) {
                "工具批次结果数量不匹配：调用 ${group.size}，结果 ${results.size}"
            }
            for (i in group.indices) {
                val call = group[i]
                val result = results[i]
                // A failed durable event append aborts the step. The Tool Settlement
                // owner later resolves an external effect instead of replaying it.
                emit.append(AgentEvent.ToolFinished(
                    turnId = turn.turnId,
                    step = step,
                    call = call,
                    output = result.content,
                    isError = result.isError,
                    errorCode = result.errorCode,
                    retryable = result.retryable,
                    sideEffect = result.sideEffect,
                    recoveryHint = result.recoveryHint,
                    retention = result.retention,
                    processExitCode = result.processExitCode,
                    fileVersions = result.fileVersions,
                    richContent = result.richContent,
                    executionId = result.executionId,
                ))
                turn.appendToolResult(call, result)
            }
            index += group.size
        }
    }
}

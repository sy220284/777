package com.labteto.dshmobile.harness.agent

/**
 * Plugin-visible run hook. Hooks may observe or reject a run by throwing, but cannot replace the
 * immutable AgentRunContext or widen its tool/permission scope.
 */
interface AgentRunInterceptor {
    suspend fun beforeRun(context: AgentRunContext, input: String) = Unit
    suspend fun afterRun(context: AgentRunContext, result: AgentRunResult) = Unit
    suspend fun onRunFailure(context: AgentRunContext, error: Throwable) = Unit
}

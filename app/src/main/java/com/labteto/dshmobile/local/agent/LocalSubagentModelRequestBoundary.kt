package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalAgentModelRequestRuntime
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalRunModelSurface
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalWorkExecutionControl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Owns one admitted subagent provider call and its model-request resource lease. */
internal class LocalSubagentModelRequestBoundary(
    private val requestRuntime: LocalAgentModelRequestRuntime,
    private val eventLog: () -> LocalSessionEventLog,
    private val executionControl: LocalWorkExecutionControl?,
) {
    suspend fun complete(
        surface: LocalRunModelSurface,
        messages: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
    ): LocalModelReply {
        return try {
            requestRuntime.complete(
                surface = surface,
                messages = messages,
                tools = tools,
                executionControl = executionControl,
            )
        } catch (error: LocalModelException) {
            logSubagentProviderError(eventLog(), subagentId, step, error)
            throw error
        }
    }
}

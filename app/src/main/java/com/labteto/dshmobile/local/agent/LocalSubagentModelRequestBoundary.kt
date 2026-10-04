package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalAgentModelRequestRuntime
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalRunModelSurface
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalWorkExecutionControl
import com.labteto.dshmobile.local.executeWithModelAdmission
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
    ): LocalModelReply = executeWithModelAdmission(
        control = executionControl,
        routeFingerprint = surface.routeFingerprint,
        model = surface.model,
        baseUrl = surface.baseUrl,
        contextWindowTokensOverride = surface.contextWindowTokensOverride,
        messages = messages,
        tools = tools,
    ) {
        try {
            requestRuntime.complete(
                surface = surface,
                messages = messages,
                tools = tools,
            )
        } catch (error: LocalModelException) {
            logSubagentProviderError(eventLog(), subagentId, step, error)
            throw error
        }
    }
}

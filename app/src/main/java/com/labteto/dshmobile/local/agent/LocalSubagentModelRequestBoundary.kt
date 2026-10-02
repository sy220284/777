package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalWorkExecutionControl
import com.labteto.dshmobile.local.executeWithModelAdmission
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Owns one admitted subagent provider call and its model-request resource lease. */
internal class LocalSubagentModelRequestBoundary(
    private val modelGateway: LocalModelGateway,
    private val resourceScheduler: HarnessResourceScheduler,
    private val eventLog: () -> LocalSessionEventLog,
    private val executionControl: LocalWorkExecutionControl?,
) {
    suspend fun complete(
        profile: LocalModelProfile,
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
    ): LocalModelReply = executeWithModelAdmission(
        control = executionControl,
        profileId = profile.id,
        model = model,
        baseUrl = baseUrl,
        messages = messages,
        tools = tools,
    ) {
        resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
            try {
                modelGateway.complete(
                    profile = profile,
                    model = model,
                    baseUrl = baseUrl,
                    messages = messages,
                    tools = tools,
                )
            } catch (error: LocalModelException) {
                logSubagentProviderError(eventLog(), subagentId, step, error)
                throw error
            }
        }
    }
}

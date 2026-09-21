package com.labteto.dshmobile.reference

import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentLoop
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class NativeConformanceRunner {
    suspend fun run(vector: ConformanceVector): List<CanonicalEvent> {
        require(vector.modelReplies.isNotEmpty()) { "差分向量至少需要一个模型回复" }

        val events = mutableListOf<AgentEvent>()
        var replyIndex = 0
        val loop = AgentLoop(
            model = AgentModel {
                val fixture = vector.modelReplies.getOrNull(replyIndex)
                    ?: error("模型回复脚本已耗尽：${vector.id}")
                replyIndex += 1
                AgentModelReply(
                    content = fixture.content,
                    toolCalls = fixture.toolCalls.map { call ->
                        AgentToolCall(
                            id = call.id,
                            name = call.name,
                            arguments = call.arguments,
                        )
                    },
                )
            },
            tools = AgentToolExecutor { call ->
                vector.toolOutputs[call.name]
                    ?: error("缺少工具输出：${call.name}")
            },
            eventSink = AgentEventSink { events += it },
            idFactory = { "reference-turn" },
        )

        loop.run(vector.input)
        check(replyIndex == vector.modelReplies.size) {
            "模型回复脚本未完全消费：${vector.id}，已消费 $replyIndex/${vector.modelReplies.size}"
        }
        return AgentEventCanonicalizer.canonicalize(events)
    }
}

object AgentEventCanonicalizer {
    private val json = Json

    fun canonicalize(events: List<AgentEvent>): List<CanonicalEvent> =
        events.map { event ->
            when (event) {
                is AgentEvent.TurnStarted -> CanonicalEvent(type = "turn/start")
                is AgentEvent.AssistantObserved -> CanonicalEvent(
                    type = "assistant/message",
                    step = event.step,
                    content = event.content.takeIf { it.isNotEmpty() },
                )
                is AgentEvent.ToolStarted -> CanonicalEvent(
                    type = "tool/call",
                    step = event.step,
                    callId = event.call.id,
                    name = event.call.name,
                    arguments = canonicalJson(event.call.arguments),
                )
                is AgentEvent.ToolFinished -> CanonicalEvent(
                    type = "tool/result",
                    step = event.step,
                    callId = event.call.id,
                    content = event.output.takeIf { it.isNotEmpty() },
                )
                is AgentEvent.TurnCompleted -> CanonicalEvent(
                    type = "turn/end",
                    reason = "completed",
                )
                is AgentEvent.TurnFailed -> CanonicalEvent(
                    type = "turn/end",
                    reason = "error",
                )
                is AgentEvent.TurnCancelled -> CanonicalEvent(
                    type = "turn/end",
                    reason = "aborted",
                )
            }
        }

    private fun canonicalJson(value: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), sortJson(value))

    private fun sortJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries
                .sortedBy { it.key }
                .associate { (key, child) -> key to sortJson(child) },
        )
        is JsonArray -> JsonArray(value.map(::sortJson))
        else -> value
    }
}

@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One immutable entry in the session log. The envelope fields (`type`, `seq`, `time`) are
 * strict; `data` is typed per event kind. Unknown event types fall back to
 * [UnknownSessionEvent] carrying the raw payload.
 *
 * Harness 0.1.3 (session format v2) removed `assistant/chunk` from the durable vocabulary and
 * added `assistant/attempt`; a model's deltas now ride the live assistant stream only
 * (`SessionHistoryDtos.kt`) and settle into one event per attempt.
 */
@Serializable(with = SessionEventSerializer::class)
sealed class SessionEvent {
    /** The wire event type. */
    abstract val type: String

    /** Monotonic sequence number within the session. */
    abstract val seq: Int

    /** Unix epoch milliseconds. */
    abstract val time: Long

    /**
     * Seq numbers of earlier events this event cites as sources; present only on
     * surface events (`user/message`, `assistant/message`, `tool/result`).
     */
    abstract val sourceEventSeqs: List<Int>?

    /** How this event entered the ordered surface ('append' or a replace op); surface events only. */
    abstract val surfaceOp: JsonElement?

    /** Marks an event a reader may safely skip when it does not recognize `type`. */
    abstract val ignorable: Boolean?

    @Serializable
    @SerialName("turn/start")
    data class TurnStart(
        @SerialName("type") override val type: String = "turn/start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: TurnStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("turn/end")
    data class TurnEnd(
        @SerialName("type") override val type: String = "turn/end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: TurnEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("step/start")
    data class StepStart(
        @SerialName("type") override val type: String = "step/start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: StepStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("step/end")
    data class StepEnd(
        @SerialName("type") override val type: String = "step/end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: StepEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("user/message")
    data class UserMessage(
        @SerialName("type") override val type: String = "user/message",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: MessageData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("assistant/attempt")
    data class AssistantAttempt(
        @SerialName("type") override val type: String = "assistant/attempt",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: AssistantAttemptData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("assistant/message")
    data class AssistantMessage(
        @SerialName("type") override val type: String = "assistant/message",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: AssistantMessageData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool/call")
    data class ToolCall(
        @SerialName("type") override val type: String = "tool/call",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolCallData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool/result")
    data class ToolResult(
        @SerialName("type") override val type: String = "tool/result",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolResultData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("todo/write")
    data class TodoWrite(
        @SerialName("type") override val type: String = "todo/write",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: TodoWriteData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("request/header")
    data class RequestHeader(
        @SerialName("type") override val type: String = "request/header",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: RequestHeaderData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("request/context")
    data class RequestContext(
        @SerialName("type") override val type: String = "request/context",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: RequestContextData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("session/end-seed")
    data class SessionEndSeed(
        @SerialName("type") override val type: String = "session/end-seed",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: SessionEndSeedData = SessionEndSeedData(),
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("goal/change")
    data class GoalChange(
        @SerialName("type") override val type: String = "goal/change",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: GoalChangeData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("plan/mode")
    data class PlanMode(
        @SerialName("type") override val type: String = "plan/mode",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: PlanModeData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/run-start")
    data class ToolWorkflowRunStart(
        @SerialName("type") override val type: String = "tool-workflow/run-start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowRunStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/agent-start")
    data class ToolWorkflowAgentStart(
        @SerialName("type") override val type: String = "tool-workflow/agent-start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowAgentStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/agent-end")
    data class ToolWorkflowAgentEnd(
        @SerialName("type") override val type: String = "tool-workflow/agent-end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowAgentEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/run-end")
    data class ToolWorkflowRunEnd(
        @SerialName("type") override val type: String = "tool-workflow/run-end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowRunEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("schedule/change")
    data class ScheduleChange(
        @SerialName("type") override val type: String = "schedule/change",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ScheduleChangeData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/start")
    data class CompactionStart(
        @SerialName("type") override val type: String = "compaction/start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/summary")
    data class CompactionSummary(
        @SerialName("type") override val type: String = "compaction/summary",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionSummaryData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/end")
    data class CompactionEnd(
        @SerialName("type") override val type: String = "compaction/end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/prune")
    data class CompactionPrune(
        @SerialName("type") override val type: String = "compaction/prune",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionPruneData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("subagent/descriptor")
    data class SubagentDescriptor(
        @SerialName("type") override val type: String = "subagent/descriptor",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: SubagentDescriptorData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("feedback/record")
    data class FeedbackRecord(
        @SerialName("type") override val type: String = "feedback/record",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: FeedbackRecordData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("approval/asked")
    data class ApprovalAsked(
        @SerialName("type") override val type: String = "approval/asked",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ApprovalAskedData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("approval/decided")
    data class ApprovalDecided(
        @SerialName("type") override val type: String = "approval/decided",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ApprovalDecidedData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("approval/policy")
    data class ApprovalPolicy(
        @SerialName("type") override val type: String = "approval/policy",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ApprovalPolicyData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()
}

/** A session event of an unknown `type`; the complete raw envelope is preserved. */
data class UnknownSessionEvent(
    override val type: String,
    override val seq: Int,
    override val time: Long,
    /** The complete raw envelope JSON (including `type`/`seq`/`time`). */
    val raw: JsonElement,
    override val sourceEventSeqs: List<Int>? = null,
    override val surfaceOp: JsonElement? = null,
    override val ignorable: Boolean? = null,
) : SessionEvent()

/** Custom `type`-dispatching serializer for [SessionEvent]; unknown types pass through raw. */
object SessionEventSerializer : KSerializer<SessionEvent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionEvent") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
        element("seq", buildSerialDescriptor("kotlin.Int", PrimitiveKind.INT))
        element("time", buildSerialDescriptor("kotlin.Long", PrimitiveKind.LONG))
        element("data", buildSerialDescriptor("kotlin.Any", SerialKind.CONTEXTUAL))
    }

    override fun serialize(encoder: Encoder, value: SessionEvent) {
        val json: JsonElement = when (value) {
            is SessionEvent.TurnStart -> encodeToJsonElement(SessionEvent.TurnStart.serializer(), value)
            is SessionEvent.TurnEnd -> encodeToJsonElement(SessionEvent.TurnEnd.serializer(), value)
            is SessionEvent.StepStart -> encodeToJsonElement(SessionEvent.StepStart.serializer(), value)
            is SessionEvent.StepEnd -> encodeToJsonElement(SessionEvent.StepEnd.serializer(), value)
            is SessionEvent.UserMessage -> encodeToJsonElement(SessionEvent.UserMessage.serializer(), value)
            is SessionEvent.AssistantAttempt -> encodeToJsonElement(SessionEvent.AssistantAttempt.serializer(), value)
            is SessionEvent.AssistantMessage -> encodeToJsonElement(SessionEvent.AssistantMessage.serializer(), value)
            is SessionEvent.ToolCall -> encodeToJsonElement(SessionEvent.ToolCall.serializer(), value)
            is SessionEvent.ToolResult -> encodeToJsonElement(SessionEvent.ToolResult.serializer(), value)
            is SessionEvent.TodoWrite -> encodeToJsonElement(SessionEvent.TodoWrite.serializer(), value)
            is SessionEvent.RequestHeader -> encodeToJsonElement(SessionEvent.RequestHeader.serializer(), value)
            is SessionEvent.RequestContext -> encodeToJsonElement(SessionEvent.RequestContext.serializer(), value)
            is SessionEvent.SessionEndSeed -> encodeToJsonElement(SessionEvent.SessionEndSeed.serializer(), value)
            is SessionEvent.GoalChange -> encodeToJsonElement(SessionEvent.GoalChange.serializer(), value)
            is SessionEvent.PlanMode -> encodeToJsonElement(SessionEvent.PlanMode.serializer(), value)
            is SessionEvent.ToolWorkflowRunStart -> encodeToJsonElement(SessionEvent.ToolWorkflowRunStart.serializer(), value)
            is SessionEvent.ToolWorkflowAgentStart -> encodeToJsonElement(SessionEvent.ToolWorkflowAgentStart.serializer(), value)
            is SessionEvent.ToolWorkflowAgentEnd -> encodeToJsonElement(SessionEvent.ToolWorkflowAgentEnd.serializer(), value)
            is SessionEvent.ToolWorkflowRunEnd -> encodeToJsonElement(SessionEvent.ToolWorkflowRunEnd.serializer(), value)
            is SessionEvent.ScheduleChange -> encodeToJsonElement(SessionEvent.ScheduleChange.serializer(), value)
            is SessionEvent.CompactionStart -> encodeToJsonElement(SessionEvent.CompactionStart.serializer(), value)
            is SessionEvent.CompactionSummary -> encodeToJsonElement(SessionEvent.CompactionSummary.serializer(), value)
            is SessionEvent.CompactionEnd -> encodeToJsonElement(SessionEvent.CompactionEnd.serializer(), value)
            is SessionEvent.CompactionPrune -> encodeToJsonElement(SessionEvent.CompactionPrune.serializer(), value)
            is SessionEvent.SubagentDescriptor -> encodeToJsonElement(SessionEvent.SubagentDescriptor.serializer(), value)
            is SessionEvent.FeedbackRecord -> encodeToJsonElement(SessionEvent.FeedbackRecord.serializer(), value)
            is SessionEvent.ApprovalAsked -> encodeToJsonElement(SessionEvent.ApprovalAsked.serializer(), value)
            is SessionEvent.ApprovalDecided -> encodeToJsonElement(SessionEvent.ApprovalDecided.serializer(), value)
            is SessionEvent.ApprovalPolicy -> encodeToJsonElement(SessionEvent.ApprovalPolicy.serializer(), value)
            is UnknownSessionEvent -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionEvent {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: ""
        val seq = json["seq"]?.jsonPrimitive?.intOrNull ?: 0
        val time = json["time"]?.jsonPrimitive?.longOrNull ?: 0L
        val sourceEventSeqs = json["sourceEventSeqs"]?.let { element ->
            decodeFromJsonElement<List<Int>>(element)
        }
        val surfaceOp = json["surfaceOp"]
        val ignorable = json["ignorable"]?.jsonPrimitive?.booleanOrNull
        return when (type) {
            "turn/start" -> decodeFromJsonElement(SessionEvent.TurnStart.serializer(), json)
            "turn/end" -> decodeFromJsonElement(SessionEvent.TurnEnd.serializer(), json)
            "step/start" -> decodeFromJsonElement(SessionEvent.StepStart.serializer(), json)
            "step/end" -> decodeFromJsonElement(SessionEvent.StepEnd.serializer(), json)
            "user/message" -> decodeFromJsonElement(SessionEvent.UserMessage.serializer(), json)
            "assistant/attempt" -> decodeFromJsonElement(SessionEvent.AssistantAttempt.serializer(), json)
            "assistant/message" -> decodeFromJsonElement(SessionEvent.AssistantMessage.serializer(), json)
            "tool/call" -> decodeFromJsonElement(SessionEvent.ToolCall.serializer(), json)
            "tool/result" -> decodeFromJsonElement(SessionEvent.ToolResult.serializer(), json)
            "todo/write" -> decodeFromJsonElement(SessionEvent.TodoWrite.serializer(), json)
            "request/header" -> decodeFromJsonElement(SessionEvent.RequestHeader.serializer(), json)
            "request/context" -> decodeFromJsonElement(SessionEvent.RequestContext.serializer(), json)
            "session/end-seed" -> decodeFromJsonElement(SessionEvent.SessionEndSeed.serializer(), json)
            "goal/change" -> decodeFromJsonElement(SessionEvent.GoalChange.serializer(), json)
            "plan/mode" -> decodeFromJsonElement(SessionEvent.PlanMode.serializer(), json)
            "tool-workflow/run-start" -> decodeFromJsonElement(SessionEvent.ToolWorkflowRunStart.serializer(), json)
            "tool-workflow/agent-start" -> decodeFromJsonElement(SessionEvent.ToolWorkflowAgentStart.serializer(), json)
            "tool-workflow/agent-end" -> decodeFromJsonElement(SessionEvent.ToolWorkflowAgentEnd.serializer(), json)
            "tool-workflow/run-end" -> decodeFromJsonElement(SessionEvent.ToolWorkflowRunEnd.serializer(), json)
            "schedule/change" -> decodeFromJsonElement(SessionEvent.ScheduleChange.serializer(), json)
            "compaction/start" -> decodeFromJsonElement(SessionEvent.CompactionStart.serializer(), json)
            "compaction/summary" -> decodeFromJsonElement(SessionEvent.CompactionSummary.serializer(), json)
            "compaction/end" -> decodeFromJsonElement(SessionEvent.CompactionEnd.serializer(), json)
            "compaction/prune" -> decodeFromJsonElement(SessionEvent.CompactionPrune.serializer(), json)
            "subagent/descriptor" -> decodeFromJsonElement(SessionEvent.SubagentDescriptor.serializer(), json)
            "feedback/record" -> decodeFromJsonElement(SessionEvent.FeedbackRecord.serializer(), json)
            "approval/asked" -> decodeFromJsonElement(SessionEvent.ApprovalAsked.serializer(), json)
            "approval/decided" -> decodeFromJsonElement(SessionEvent.ApprovalDecided.serializer(), json)
            "approval/policy" -> decodeFromJsonElement(SessionEvent.ApprovalPolicy.serializer(), json)
            else -> UnknownSessionEvent(type, seq, time, json, sourceEventSeqs, surfaceOp, ignorable)
        }
    }
}

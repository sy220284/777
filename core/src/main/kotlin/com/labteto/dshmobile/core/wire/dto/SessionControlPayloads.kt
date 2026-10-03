package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry in an agent's todo list — the unit of the `todo/write` whole-list snapshot. */
@Serializable
data class TodoItem(
    /** What this task is — a short imperative line shown in the UI. */
    @SerialName("content") val content: String,
    /** Lifecycle state ('pending' | 'in_progress' | 'completed'). */
    @SerialName("status") val status: String,
)

/** `todo/write` payload — whole-list snapshot; latest write wins on replay. */
@Serializable
data class TodoWriteData(
    @SerialName("todos") val todos: List<TodoItem> = emptyList(),
)

/** `request/header` payload — full header for the next request. */
@Serializable
data class RequestHeaderData(
    @SerialName("header") val header: EpochHeader,
    @SerialName("reason") val reason: RequestHeaderReason,
)

/** `request/context` payload — route metadata for the next request. */
@Serializable
data class RequestContextData(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    /** Maximum combined request and response context in tokens, when advertised. */
    @SerialName("contextWindow") val contextWindow: Int? = null,
)

/**
 * `session/end-seed` payload. Empty through 0.1.2; since 0.1.3 a fork child's marker at its
 * exact inherited-prefix cut carries `inherited: true`, and untagged markers keep the ordinary
 * restore and replay boundaries.
 */
@Serializable
data class SessionEndSeedData(
    @SerialName("inherited") val inherited: Boolean? = null,
)

/** `goal/change` payload — complete post-mutation state or clear tombstone. */
@Serializable
data class GoalChangeData(
    @SerialName("kind") val kind: String = "goal/change",
    @SerialName("version") val version: Int = 1,
    /** 'create' | 'edit' | 'pause' | 'resume' | 'complete' | 'block' | 'clear'. */
    @SerialName("operation") val operation: String,
    /** Present for non-clear operations: the full durable goal snapshot. */
    @SerialName("goal") val goal: GoalSnapshot? = null,
    @SerialName("roundsStarted") val roundsStarted: Int? = null,
    @SerialName("createdAt") val createdAt: Long? = null,
    @SerialName("updatedAt") val updatedAt: Long? = null,
    /** Present for the clear tombstone. */
    @SerialName("cleared") val cleared: GoalRef? = null,
    @SerialName("clearedAt") val clearedAt: Long? = null,
)

/** `plan/mode` payload — whether plan mode is in force from this point on. */
@Serializable
data class PlanModeData(
    @SerialName("active") val active: Boolean,
)

/** `feedback/record` payload — one recorded human remark about this session. */
@Serializable
data class FeedbackRecordData(
    @SerialName("text") val text: String,
)

/** `approval/asked` payload — an approval question was put to the answerer chain. */
@Serializable
data class ApprovalAskedData(
    @SerialName("id") val id: String,
    @SerialName("toolName") val toolName: String,
    @SerialName("callId") val callId: String? = null,
    @SerialName("reason") val reason: String? = null,
)

/** `approval/decided` payload — the outcome of a prior `approval/asked`. */
@Serializable
data class ApprovalDecidedData(
    @SerialName("id") val id: String,
    /** 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'. */
    @SerialName("outcome") val outcome: String,
)

/** `approval/policy` payload — the session's approval policy was switched. */
@Serializable
data class ApprovalPolicyData(
    /** 'ask' | 'never'. */
    @SerialName("policy") val policy: String,
    /** Marks an override seeded into a child at delegation. */
    @SerialName("source") val source: String? = null,
)

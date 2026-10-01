package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.dto.ApprovalOutcome
import com.labteto.dshmobile.core.wire.dto.ApprovalRequestEvent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.QUESTION_CANCELLED
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.RemoteEventRejection
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Owns remote approval/question waterfalls and optimistic permission selection. */
internal class SessionInteractionRuntime(
    private val lock: Any,
    private val apiProvider: () -> DshApiClient?,
    private val clientIdProvider: () -> String?,
    private val currentSessionId: () -> String?,
    private val addPending: (String, String) -> Unit,
    private val removePending: (String, String) -> Unit,
    private val emitSessions: () -> Unit,
    private val logger: (String) -> Unit,
) {
    private val pending = PendingSessionInteractionStore()
    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()
    private val _pendingQuestions = MutableStateFlow<PendingQuestions?>(null)
    val pendingQuestions: StateFlow<PendingQuestions?> = _pendingQuestions.asStateFlow()
    private val _pendingPermission = MutableStateFlow<String?>(null)
    val pendingPermission: StateFlow<String?> = _pendingPermission.asStateFlow()

    fun syncVisible() = synchronized(lock) { syncVisibleLocked() }

    fun settlePermission(value: String?) = synchronized(lock) {
        val sessionId = currentSessionId() ?: return@synchronized
        val optimistic = pending.permissionForSession(sessionId) ?: return@synchronized
        if (value == optimistic && pending.forgetPermission(sessionId, optimistic)) syncVisibleLocked()
    }

    fun clearRetiredGeneration() = synchronized(lock) {
        pending.clear().forEach { sessionId ->
            removePending(sessionId, "approval")
            removePending(sessionId, "question")
            removePending(sessionId, "plan-review")
        }
        emitSessions()
        syncVisibleLocked()
    }

    fun installApproval(eventId: String, sessionId: String, request: ApprovalRequestEvent) =
        synchronized(lock) {
            pending.installApproval(eventId, sessionId, request)
            addPending(sessionId, "approval")
            emitSessions()
            syncVisibleLocked()
        }

    fun installQuestions(eventId: String, sessionId: String, questions: List<AskUserQuestionItem>) =
        synchronized(lock) {
            val kind = pending.installQuestions(eventId, sessionId, questions)
            removePending(sessionId, "question")
            removePending(sessionId, "plan-review")
            addPending(sessionId, kind)
            emitSessions()
            syncVisibleLocked()
        }

    fun discardSession(sessionId: String) = synchronized(lock) {
        pending.discardSession(sessionId)
        syncVisibleLocked()
    }

    fun forgetEvent(eventId: String) = synchronized(lock) {
        val removed = pending.forgetEvent(eventId) ?: return@synchronized
        removed.pendingKinds.forEach { removePending(removed.sessionId, it) }
        emitSessions()
        syncVisibleLocked()
    }

    fun installPermission(sessionId: String, value: String) = synchronized(lock) {
        pending.installPermission(sessionId, value)
        syncVisibleLocked()
    }

    fun clearPermission(sessionId: String, value: String) = synchronized(lock) {
        if (pending.forgetPermission(sessionId, value)) syncVisibleLocked()
    }

    suspend fun respondApproval(sessionId: String, approvalId: String, allow: Boolean): QuestionOutcome {
        val api = apiProvider() ?: return QuestionOutcome.Unsent
        val request = synchronized(lock) { pending.approvalForEvent(approvalId) }
        if (request == null) {
            logger("no pending approval for id $approvalId")
            if (_pendingApproval.value?.approvalId == approvalId) _pendingApproval.value = null
            return QuestionOutcome.Refused(NOT_PENDING)
        }
        if (!approvalResponseMatchesSession(request.sessionId, sessionId)) {
            logger("refusing approval $approvalId for session $sessionId; owner=${request.sessionId}")
            return QuestionOutcome.Refused(NOT_PENDING)
        }
        val clientId = clientIdProvider() ?: run {
            logger("cannot answer approval $approvalId: no connection generation")
            return QuestionOutcome.Unsent
        }
        val outcome = if (allow) ApprovalOutcome.ALLOWED_ONCE else ApprovalOutcome.REJECTED
        val result = api.answerEvent(
            clientId = clientId,
            eventId = request.rpcId,
            outcome = RemoteEventOutcome.Result(value = JsonPrimitive(outcome)),
        )
        return answerOutcome(result, "approval response", sessionId) { forgetEvent(request.rpcId) }
    }

    suspend fun answerQuestions(sessionId: String, answer: AskUserQuestionAnswer): QuestionOutcome {
        val api = apiProvider() ?: return QuestionOutcome.Unsent
        val eventId = pendingQuestionEvent(sessionId) ?: return abandonQuestions(sessionId)
        val clientId = clientIdProvider() ?: return QuestionOutcome.Unsent
        return answerOutcome(
            api.answerEvent(
                clientId = clientId,
                eventId = eventId,
                outcome = RemoteEventOutcome.Result(
                    value = encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer),
                ),
            ),
            "question response",
            sessionId,
        ) { forgetEvent(eventId) }
    }

    suspend fun dismissQuestions(sessionId: String): QuestionOutcome {
        val api = apiProvider() ?: return QuestionOutcome.Unsent
        val eventId = pendingQuestionEvent(sessionId) ?: return abandonQuestions(sessionId)
        val clientId = clientIdProvider() ?: return QuestionOutcome.Unsent
        return answerOutcome(
            api.answerEvent(
                clientId = clientId,
                eventId = eventId,
                outcome = RemoteEventOutcome.Rejected(
                    error = RemoteEventRejection(
                        name = "UserQuestionError",
                        message = QUESTION_CANCELLED.message,
                        code = QUESTION_CANCELLED.code,
                    ),
                ),
            ),
            "question dismissal",
            sessionId,
        ) { forgetEvent(eventId) }
    }

    private fun pendingQuestionEvent(sessionId: String): String? {
        val eventId = synchronized(lock) { pending.questionEventForSession(sessionId) }
        if (eventId == null) logger("no pending question for session $sessionId")
        return eventId
    }

    private fun abandonQuestions(sessionId: String): QuestionOutcome {
        synchronized(lock) {
            if (pending.forgetQuestions(sessionId, null)) {
                removePending(sessionId, "question")
                removePending(sessionId, "plan-review")
                emitSessions()
                syncVisibleLocked()
            }
        }
        return QuestionOutcome.Refused(NOT_PENDING)
    }

    private fun answerOutcome(
        result: RpcResult<JsonElement>,
        what: String,
        sessionId: String,
        forget: () -> Unit,
    ): QuestionOutcome {
        val outcome = when (result) {
            is RpcResult.Ok -> QuestionOutcome.Accepted
            is RpcResult.Err -> {
                logger("$what failed for $sessionId: ${result.error.code}: ${result.error.message}")
                if (TransportFailures.of(result.error) != null) QuestionOutcome.Unsent
                else QuestionOutcome.Refused(result.error.code)
            }
        }
        if (settlesRequest(outcome)) forget()
        return outcome
    }

    private fun syncVisibleLocked() {
        val sessionId = currentSessionId()
        _pendingApproval.value = pending.approvalForSession(sessionId)
        _pendingQuestions.value = pending.questionsForSession(sessionId)
        _pendingPermission.value = pending.permissionForSession(sessionId)
    }
}

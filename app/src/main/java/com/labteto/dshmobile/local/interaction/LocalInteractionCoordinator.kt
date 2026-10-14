package com.labteto.dshmobile.local.interaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

internal const val LOCAL_QUESTION_CANCELLED_RESPONSE = "用户取消了问题"
private const val LOCAL_QUESTION_EMPTY_RESPONSE = "用户未提供文字回答"

/**
 * Narrow state surface owned by one interaction runtime.
 *
 * Approval/question coordination must not know the aggregate app state. Implementations expose only
 * the interaction facts required by this lifecycle, so foreground and detached Work can share the
 * same semantics without receiving a writable application aggregate.
 */
internal interface LocalInteractionStatePort {
    fun pendingQuestion(): LocalQuestion?
    fun pendingApproval(): LocalApproval?
    fun setPendingApproval(approval: LocalApproval?)
    fun clearPendingApproval(callId: String)
    fun setPendingQuestion(question: LocalQuestion?)
    fun clearPendingQuestion(callId: String)
    fun clearPendingInteractions()
    fun deviceApprovalLeaseEnabled(): Boolean
    fun setDeviceApprovalLease(enabled: Boolean)
}

/**
 * Owns all user-mediated wait/response state for one local Harness run.
 *
 * One interaction mutex preserves the current UI contract of showing a single decision at a time.
 * UI approvals bind to an owner-issued waiter identity; model call IDs may be reused.
 * Internal policy changes and existing question handling keep their call-id contract.
 */
internal class LocalInteractionCoordinator(
    private val state: LocalInteractionStatePort,
) {
    private data class ApprovalWaiter(
        val approval: LocalApproval,
        val response: CompletableDeferred<Boolean>,
    ) {
        val callId: String get() = approval.callId
    }

    private data class QuestionWaiter(
        val question: LocalQuestion,
        val response: CompletableDeferred<String>,
    ) { val callId: String get() = question.callId }

    private val interactionMutex = Mutex()
    private val waiterLock = Any()
    private var approvalWaiter: ApprovalWaiter? = null
    private var questionWaiter: QuestionWaiter? = null
    private var cancellationGeneration = 0L

    internal fun pendingApproval(): LocalApproval? = state.pendingApproval()

    internal fun deviceApprovalLeaseEnabled(): Boolean = state.deviceApprovalLeaseEnabled()

    internal fun setDeviceApprovalLease(enabled: Boolean) {
        state.setDeviceApprovalLease(enabled)
    }

    suspend fun awaitApproval(approval: LocalApproval): Boolean {
        val generation = synchronized(waiterLock) { cancellationGeneration }
        interactionMutex.lock()
        try {
            val response = CompletableDeferred<Boolean>()
            val activeApproval = approval.copy(requestId = UUID.randomUUID().toString())
            synchronized(waiterLock) {
                if (generation != cancellationGeneration) {
                    throw CancellationException("用户交互所属回合已取消")
                }
                check(approvalWaiter == null && questionWaiter == null) {
                    "已有用户交互正在等待处理"
                }
                approvalWaiter = ApprovalWaiter(activeApproval, response)
            }
            state.setPendingApproval(activeApproval)
            return try {
                response.await()
            } finally {
                synchronized(waiterLock) {
                    if (approvalWaiter?.callId == approval.callId) approvalWaiter = null
                }
                state.clearPendingApproval(approval.callId)
            }
        } finally {
            interactionMutex.unlock()
        }
    }

    suspend fun awaitQuestion(question: LocalQuestion): String {
        val generation = synchronized(waiterLock) { cancellationGeneration }
        interactionMutex.lock()
        try {
            val response = CompletableDeferred<String>()
            val activeQuestion = question.copy(requestId = UUID.randomUUID().toString())
            synchronized(waiterLock) {
                if (generation != cancellationGeneration) {
                    throw CancellationException("用户交互所属回合已取消")
                }
                check(approvalWaiter == null && questionWaiter == null) {
                    "已有用户交互正在等待处理"
                }
                questionWaiter = QuestionWaiter(activeQuestion, response)
            }
            state.setPendingQuestion(activeQuestion)
            return try {
                response.await().trim().ifBlank { LOCAL_QUESTION_EMPTY_RESPONSE }
            } finally {
                synchronized(waiterLock) {
                    if (questionWaiter?.callId == question.callId) questionWaiter = null
                }
                state.clearPendingQuestion(question.callId)
            }
        } finally {
            interactionMutex.unlock()
        }
    }

    fun answerApproval(callId: String, approved: Boolean): Boolean =
        synchronized(waiterLock) {
            val waiter = approvalWaiter
            if (waiter?.callId != callId) return@synchronized false
            waiter.response.complete(approved)
        }

    /** UI authority comes from this unresolved waiter, not from a possibly reused model call ID. */
    fun pendingApprovalForUi(): LocalApproval? = synchronized(waiterLock) {
        val waiter = approvalWaiter ?: return@synchronized null
        if (waiter.response.isCompleted || state.pendingApproval()?.requestId != waiter.approval.requestId) {
            return@synchronized null
        }
        waiter.approval
    }

    fun answerApprovalFromUi(requestId: String, approved: Boolean): Boolean = synchronized(waiterLock) {
        val waiter = approvalWaiter ?: return@synchronized false
        if (waiter.approval.requestId != requestId ||
            state.pendingApproval()?.requestId != requestId) return@synchronized false
        waiter.response.complete(approved)
    }

    /** Commit an approval mode change only while this exact unresolved request still owns the wait. */
    fun resolveApproval(callId: String, commit: (LocalApproval) -> Boolean): Boolean =
        synchronized(waiterLock) {
            val waiter = approvalWaiter
            val pending = state.pendingApproval()
            if (waiter?.callId != callId || waiter.response.isCompleted || pending?.callId != callId) {
                return@synchronized false
            }
            if (!commit(pending)) return@synchronized false
            waiter.response.complete(true)
        }

    fun pendingQuestionForUi(): LocalQuestion? = synchronized(waiterLock) {
        val waiter = questionWaiter ?: return@synchronized null
        if (waiter.response.isCompleted || state.pendingQuestion()?.requestId != waiter.question.requestId) null
        else waiter.question
    }

    fun answerQuestionFromUi(requestId: String, answer: String?): Boolean = synchronized(waiterLock) {
        val waiter = questionWaiter ?: return@synchronized false
        if (waiter.response.isCompleted || waiter.question.requestId != requestId ||
            state.pendingQuestion()?.requestId != requestId) return@synchronized false
        waiter.response.complete(answer?.trim() ?: LOCAL_QUESTION_CANCELLED_RESPONSE)
    }

    fun answerQuestion(callId: String, answer: String): Boolean =
        synchronized(waiterLock) {
            val waiter = questionWaiter
            if (waiter?.callId != callId) return@synchronized false
            waiter.response.complete(answer.trim())
        }

    fun cancelQuestion(callId: String): Boolean =
        answerQuestion(callId, LOCAL_QUESTION_CANCELLED_RESPONSE)

    fun cancelAll() {
        synchronized(waiterLock) {
            cancellationGeneration += 1L
            state.clearPendingInteractions()
            approvalWaiter?.response?.complete(false)
            questionWaiter?.response?.cancel()
        }
    }
}

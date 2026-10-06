package com.labteto.dshmobile.local.interaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

internal const val LOCAL_QUESTION_CANCELLED_RESPONSE = "用户取消了问题"
private const val LOCAL_QUESTION_EMPTY_RESPONSE = "用户未提供文字回答"

/**
 * Narrow state surface owned by one interaction runtime.
 *
 * Approval/question coordination must not know the aggregate app state. The adapter below is the
 * migration boundary for the current aggregate projection; Work-run state can replace it later
 * without changing the interaction lifecycle.
 */
internal interface LocalInteractionStatePort {
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
 * Call-id checks ensure a stale click can never resolve a later approval or question.
 */
internal class LocalInteractionCoordinator(
    private val state: LocalInteractionStatePort,
) {
    private data class ApprovalWaiter(
        val callId: String,
        val response: CompletableDeferred<Boolean>,
    )

    private data class QuestionWaiter(
        val callId: String,
        val response: CompletableDeferred<String>,
    )

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
            synchronized(waiterLock) {
                if (generation != cancellationGeneration) {
                    throw CancellationException("用户交互所属回合已取消")
                }
                check(approvalWaiter == null && questionWaiter == null) {
                    "已有用户交互正在等待处理"
                }
                approvalWaiter = ApprovalWaiter(approval.callId, response)
            }
            state.setPendingApproval(approval)
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
            synchronized(waiterLock) {
                if (generation != cancellationGeneration) {
                    throw CancellationException("用户交互所属回合已取消")
                }
                check(approvalWaiter == null && questionWaiter == null) {
                    "已有用户交互正在等待处理"
                }
                questionWaiter = QuestionWaiter(question.callId, response)
            }
            state.setPendingQuestion(question)
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

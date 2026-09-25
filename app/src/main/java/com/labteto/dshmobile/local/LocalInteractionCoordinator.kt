package com.labteto.dshmobile.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal const val LOCAL_QUESTION_CANCELLED_RESPONSE = "用户取消了问题"
private const val LOCAL_QUESTION_EMPTY_RESPONSE = "用户未提供文字回答"

/**
 * Owns all user-mediated wait/response state for the local Harness.
 *
 * One interaction mutex preserves the current UI contract of showing a single decision at a time.
 * Call-id checks ensure a stale click can never resolve a later approval or question.
 */
internal class LocalInteractionCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
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
            state.update { it.copy(pendingApproval = approval) }
            return try {
                response.await()
            } finally {
                synchronized(waiterLock) {
                    if (approvalWaiter?.callId == approval.callId) approvalWaiter = null
                }
                state.update { current ->
                    if (current.pendingApproval?.callId == approval.callId) {
                        current.copy(pendingApproval = null)
                    } else {
                        current
                    }
                }
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
            state.update { it.copy(pendingQuestion = question) }
            return try {
                response.await().trim().ifBlank { LOCAL_QUESTION_EMPTY_RESPONSE }
            } finally {
                synchronized(waiterLock) {
                    if (questionWaiter?.callId == question.callId) questionWaiter = null
                }
                state.update { current ->
                    if (current.pendingQuestion?.callId == question.callId) {
                        current.copy(pendingQuestion = null)
                    } else {
                        current
                    }
                }
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
            state.update { it.copy(pendingApproval = null, pendingQuestion = null) }
            approvalWaiter?.response?.complete(false)
            questionWaiter?.response?.cancel()
        }
    }
}

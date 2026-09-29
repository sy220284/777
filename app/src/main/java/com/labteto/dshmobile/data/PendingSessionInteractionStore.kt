package com.labteto.dshmobile.data

/**
 * Keeps remote approval/question waterfalls isolated by session while retaining event-id lookup.
 *
 * The UI exposes one card at a time for the open session, but the host may have independent
 * waterfalls pending in several sessions. Storing those cards globally would let a background
 * session overwrite the visible one and strand the earlier request.
 */
internal class PendingSessionInteractionStore {
    internal data class ApprovalRemoval(
        val request: PendingApproval,
        val sessionStillPending: Boolean,
    )

    internal data class EventRemoval(
        val sessionId: String,
        val pendingKinds: Set<String>,
    )

    private val approvalsByEvent = HashMap<String, PendingApproval>()
    private val approvalsBySession = LinkedHashMap<String, PendingApproval>()
    private val questionEvents = PendingQuestionRegistry()
    private val questionsBySession = LinkedHashMap<String, PendingQuestions>()
    private val permissionsBySession = LinkedHashMap<String, String>()

    fun installApproval(card: PendingApproval) {
        approvalsByEvent[card.approvalId] = card
        approvalsBySession[card.sessionId] = card
    }

    fun installApproval(
        eventId: String,
        sessionId: String,
        request: ApprovalRequestEvent,
    ) {
        installApproval(
            PendingApproval(
                sessionId = sessionId,
                approvalId = eventId,
                rpcId = eventId,
                toolName = request.toolName,
                reason = request.reason,
            ),
        )
    }

    fun approvalForEvent(eventId: String): PendingApproval? = approvalsByEvent[eventId]

    fun approvalForSession(sessionId: String?): PendingApproval? =
        pendingApprovalForSession(sessionId, approvalsBySession.values)

    fun forgetApproval(eventId: String): ApprovalRemoval? {
        val request = approvalsByEvent.remove(eventId) ?: return null
        val current = approvalsBySession[request.sessionId]
        if (current?.approvalId == eventId) approvalsBySession.remove(request.sessionId)
        return ApprovalRemoval(
            request = request,
            sessionStillPending = approvalsBySession.containsKey(request.sessionId),
        )
    }

    fun forgetEvent(eventId: String): EventRemoval? {
        val approval = forgetApproval(eventId)
        if (approval != null) {
            return EventRemoval(
                sessionId = approval.request.sessionId,
                pendingKinds = if (approval.sessionStillPending) emptySet() else setOf("approval"),
            )
        }
        val sessionId = questionEvents.sessionFor(eventId) ?: return null
        if (!forgetQuestions(sessionId, eventId)) return null
        return EventRemoval(sessionId, setOf("question", "plan-review"))
    }

    fun installQuestions(card: PendingQuestions) {
        questionEvents.install(card.sessionId, card.rpcId)
        questionsBySession[card.sessionId] = card
    }

    fun installQuestions(
        eventId: String,
        sessionId: String,
        questions: List<AskUserQuestionItem>,
    ): String {
        installQuestions(PendingQuestions(sessionId, eventId, questions))
        return if (questions.any { it.intent is AskUserQuestionIntent.PlanReview }) {
            "plan-review"
        } else {
            "question"
        }
    }

    fun questionSessionForEvent(eventId: String): String? = questionEvents.sessionFor(eventId)

    fun questionsForSession(sessionId: String?): PendingQuestions? =
        pendingQuestionsForSession(sessionId, questionsBySession.values)

    fun forgetQuestions(sessionId: String, eventId: String?): Boolean {
        if (!questionEvents.forget(sessionId, eventId)) return false
        val current = questionsBySession[sessionId]
        if (current == null || eventId == null || current.rpcId == eventId) {
            questionsBySession.remove(sessionId)
        }
        return true
    }

    fun installPermission(sessionId: String, value: String) {
        permissionsBySession[sessionId] = value
    }

    fun permissionForSession(sessionId: String?): String? =
        sessionId?.let(permissionsBySession::get)

    fun forgetPermission(sessionId: String, value: String): Boolean {
        if (permissionsBySession[sessionId] != value) return false
        permissionsBySession.remove(sessionId)
        return true
    }

    fun discardSession(sessionId: String) {
        approvalsByEvent.entries.removeAll { it.value.sessionId == sessionId }
        approvalsBySession.remove(sessionId)
        questionEvents.discard(sessionId)
        questionsBySession.remove(sessionId)
        permissionsBySession.remove(sessionId)
    }

    /** Clears requests bound to a retired connection generation and returns affected sessions. */
    fun clear(): Set<String> {
        val affected = linkedSetOf<String>().apply {
            approvalsBySession.keys.forEach(::add)
            questionsBySession.keys.forEach(::add)
            permissionsBySession.keys.forEach(::add)
        }
        approvalsByEvent.clear()
        approvalsBySession.clear()
        questionEvents.clear()
        questionsBySession.clear()
        permissionsBySession.clear()
        return affected
    }
}

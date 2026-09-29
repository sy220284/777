package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.ImageRejection

/** One renderable session list row (manual order, live). */
data class SessionRow(
    val sessionId: String,
    val title: String?,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String?,
    val origin: String?,
    val cwd: String?,
    val agentPreset: String?,
    val updatedAt: Long,
    val pendingInteraction: String?, // "approval" | "plan-review" | "question" | null
)

/** One renderable workspace row. */
data class WorkspaceRow(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
    /**
     * `WorkspaceView.updatedAt` as epoch millis (0 when unparseable). This stamps the *registration
     * record* — a rename or a session being added — not conversation activity, so it is only a
     * tiebreak for recency ranking, never the primary key.
     */
    val updatedAtEpoch: Long = 0L,
)

/** What a slash command did, so the caller can report it without re-reading the wire. */
sealed interface CommandOutcome {
    /** The host executed the line; [text] is its settlement message, when it produced one. */
    data class Ok(val text: String?) : CommandOutcome

    /** The line named no registered command. */
    data class Unknown(val line: String) : CommandOutcome

    /** The command ran and reported a usage or state failure. */
    data class Failed(val message: String) : CommandOutcome
}

/** What the harness did with a prompt. */
sealed interface PromptOutcome {
    /** Accepted; the turn is the transcript's business now. */
    data object Ok : PromptOutcome

    /**
     * The host refused the images. Carried as its own case because it is a composer problem, not
     * a connection problem: raising the persistent connection banner for an image that is 200px
     * too wide tells the user their harness is broken when only their picture is.
     */
    data class Rejected(val rejection: ImageRejection, val reason: String?) : PromptOutcome

    /** Anything else; the connection banner already carries [message]. */
    data class Failed(val message: String) : PromptOutcome
}

/** What the harness did with an answer to a question request, or with a dismissal of one. */
sealed interface QuestionOutcome {
    /**
     * Taken. The wait behind the card is over, so the card goes with it — the host announces that
     * resolution to every *other* client and to this one never, so nothing else can take it away.
     */
    data object Accepted : QuestionOutcome

    /**
     * Refused by the host. `bad-response` means the payload did not match the request it
     * answered; [NOT_PENDING] means the wait had already settled; anything else is the code the
     * host sent, named rather than translated — a refusal this build has never heard of is still
     * worth showing, because the wait behind it stays open either way.
     */
    data class Refused(val reason: String) : QuestionOutcome

    /** The POST never completed, so nothing is known about the wait. */
    data object Unsent : QuestionOutcome
}

/** The refusal that means the request this answer addressed is already over. */
internal const val NOT_PENDING: String = "not-pending"

internal fun approvalResponseMatchesSession(
    requestSessionId: String,
    responseSessionId: String,
): Boolean = requestSessionId == responseSessionId

/**
 * Whether [outcome] means this client is done holding the request it answered.
 *
 * Two of the three do. [QuestionOutcome.Accepted] is the host taking the answer, and
 * [QuestionOutcome.Refused] with [NOT_PENDING] is the host saying the wait had already settled —
 * one is a card that did its job and the other a card that outlived its request, and neither has
 * anything left to send. Every other refusal leaves the host's wait open with the tool call behind
 * it still blocked, so the card has to stay: it is the only thing that can still answer. So does
 * [QuestionOutcome.Unsent], where nothing is known about the wait at all and taking the card away
 * would strand the session with no way to retry.
 *
 * File-level so the rule is testable without standing up the whole store, as [nextHasMore] is.
 */
internal fun settlesRequest(outcome: QuestionOutcome): Boolean = when (outcome) {
    is QuestionOutcome.Accepted -> true
    is QuestionOutcome.Refused -> outcome.reason == NOT_PENDING
    is QuestionOutcome.Unsent -> false
}

/** A pending sandbox/permission approval the user can answer (allow-once / reject). */
data class PendingApproval(
    val sessionId: String,
    val approvalId: String,
    val rpcId: String,
    val toolName: String,
    val reason: String?,
)

/** A pending ask_user_question batch (a plan review rides the same channel via its intent). */
data class PendingQuestions(
    val sessionId: String,
    val rpcId: String,
    val items: List<AskUserQuestionItem>,
)

/**
 * Whether more history remains after folding a backwards page.
 *
 * The load-bearing clause is [freshCount]: history paging is driven by scroll position, so a page
 * that added nothing new has to end the paging regardless of what the host claims. Believing a
 * `hasMore` that a `beforeSeq` query can no longer advance past leaves the scroll trigger firing
 * against the same page forever.
 *
 * File-level so it is testable without standing up the whole store.
 */
internal fun nextHasMore(freshCount: Int, hostHasMore: Boolean, overDelivered: Boolean): Boolean =
    freshCount > 0 && (hostHasMore || overDelivered)

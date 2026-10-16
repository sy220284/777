package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Disposable incremental index over the authoritative Session EventLog. The payload stores
 * only the currently selected evidence links and their last manual decisions; it cannot
 * authorize a tool, prove test success, or replace the original event history.
 */
@Serializable
private data class RequirementEvidenceCursor(
    val goalId: String?,
    val requirements: List<String>,
    val links: List<LocalSessionEventLog.Event>,
    val reviews: List<LocalSessionEventLog.Event>,
)

internal data class IndexedRequirementEvidence(
    val links: List<LocalRequirementEvidenceLink>,
    val reviews: List<LocalRequirementEvidenceReview>,
    /** Observability for a bounded-tail regression; never claims a pass. */
    val replayedEvents: Int,
)

internal fun indexedRequirementEvidence(
    log: LocalSessionEventLog,
    todos: List<LocalTodoItem>,
    goalId: String?,
): IndexedRequirementEvidence {
    val requirements = todos.map(LocalTodoItem::content)
    val generation = log.resetGeneration
    val checkpoint = log.readProjectionCheckpoint(INDEX_NAME, INDEX_VERSION)
    val restored = checkpoint?.let {
        val decoded = Json.decodeFromString(RequirementEvidenceCursor.serializer(), it.payload)
        decoded.takeIf { value ->
            value.goalId == goalId && value.requirements == requirements
        }
    }
    val links = linkedMapOf<Pair<Int, String>, LocalSessionEventLog.Event>()
    val reviews = linkedMapOf<Long, LocalSessionEventLog.Event>()

    fun projectLinks(): List<LocalRequirementEvidenceLink> =
        projectRequirementEvidenceLinks(links.values.asSequence(), todos, goalId)

    fun consume(event: LocalSessionEventLog.Event) {
        when (event.type) {
            "work/requirement-evidence" -> {
                val candidate = projectRequirementEvidenceLinks(sequenceOf(event), todos, goalId)
                    .singleOrNull() ?: return
                val key = candidate.requirementIndex to candidate.artifactPath
                val old = links.put(key, event)
                if (old != null) reviews.remove(old.sequence)
                require(links.size <= MAX_ACTIVE_EVIDENCE) {
                    "Work 证据关联数量超过 $MAX_ACTIVE_EVIDENCE 项，请归档旧目标"
                }
            }
            "work/requirement-evidence-review" -> {
                val current = projectRequirementEvidenceReviews(
                    sequenceOf(event), projectLinks(), goalId,
                ).singleOrNull() ?: return
                reviews[current.linkSequence] = event
            }
        }
    }

    // A reusable checkpoint is scoped to this exact goal and requirement list.
    // An edited goal contract forces an original-log replay, never a cross-goal reuse.
    restored?.links?.forEach(::consume)
    restored?.reviews?.forEach(::consume)
    val through = if (restored == null) -1L else requireNotNull(checkpoint).throughSequence
    var cursor = through
    val last = log.latestSequence()
    var replayed = 0
    while (cursor < last) {
        val page = log.pageAfter(cursor, PAGE_SIZE).takeWhile { it.sequence <= last }
        check(page.isNotEmpty() && page.last().sequence > cursor) {
            "Work 证据日志游标无法前进：$cursor -> $last"
        }
        page.forEach(::consume)
        replayed += page.size
        cursor = page.last().sequence
    }
    check(!log.isClosed && log.resetGeneration == generation) {
        "Work 证据投影期间会话日志已经重置或关闭"
    }
    val projectedLinks = projectLinks()
    val projectedReviews = projectRequirementEvidenceReviews(
        reviews.values.asSequence(), projectedLinks, goalId,
    )
    if (cursor != through) {
        log.writeProjectionCheckpoint(
            INDEX_NAME, INDEX_VERSION, cursor,
            Json.encodeToString(RequirementEvidenceCursor.serializer(),
                RequirementEvidenceCursor(goalId, requirements, links.values.toList(),
                    reviews.values.toList())),
            generation,
        )
    }
    return IndexedRequirementEvidence(projectedLinks, projectedReviews, replayed)
}

private const val INDEX_NAME = "work.requirement-evidence"
private const val INDEX_VERSION = 1
private const val PAGE_SIZE = 256
private const val MAX_ACTIVE_EVIDENCE = 2_048

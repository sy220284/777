package com.labteto.dshmobile.local

/**
 * Incremental reverse pager for the user-visible transcript stored in Session Event.
 *
 * The cursor carries only the unresolved projection state needed to continue walking older events.
 * A message replacement is remembered until its old id is encountered; an active-transcript event
 * is a hard materialization boundary once its own transcript has been exhausted.
 */
internal data class LocalTranscriptPageCursor(
    val beforeSequenceExclusive: Long = Long.MAX_VALUE,
    val resumeEventSequence: Long? = null,
    val resumeMessageIndex: Int? = null,
    val resumeActiveTranscript: Boolean = false,
    val suppressedMessageIds: Set<String> = emptySet(),
)

internal data class LocalTranscriptPage(
    val messages: List<LocalHarnessMessage>,
    val nextCursor: LocalTranscriptPageCursor?,
)

internal class LocalSessionTranscriptPager(
    private val eventLog: LocalSessionEventLog,
    private val eventPageSize: Int = DEFAULT_EVENT_PAGE_SIZE,
) {
    fun all(
        pageSize: Int = MAX_MESSAGE_PAGE_SIZE,
    ): List<LocalHarnessMessage> {
        val pages = mutableListOf<List<LocalHarnessMessage>>()
        var cursor: LocalTranscriptPageCursor? = null
        do {
            val page = page(cursor = cursor, limit = pageSize)
            if (page.messages.isNotEmpty()) pages += page.messages
            cursor = page.nextCursor
        } while (cursor != null)
        return pages.asReversed().flatten()
    }

    fun page(
        cursor: LocalTranscriptPageCursor? = null,
        limit: Int = DEFAULT_MESSAGE_PAGE_SIZE,
    ): LocalTranscriptPage {
        val messageLimit = limit.coerceIn(1, MAX_MESSAGE_PAGE_SIZE)
        val scanPageSize = eventPageSize.coerceIn(1, MAX_EVENT_PAGE_SIZE)
        val collectedNewestFirst = ArrayList<LocalHarnessMessage>(messageLimit)
        val suppressed = cursor?.suppressedMessageIds?.toMutableSet() ?: linkedSetOf()
        var beforeSequenceExclusive = cursor?.beforeSequenceExclusive ?: Long.MAX_VALUE

        cursor?.resumeEventSequence?.let { sequence ->
            val event = eventLog.pageBefore(sequence + 1L, 1)
                .singleOrNull { it.sequence == sequence }
            if (event != null) {
                val decoded = decodeTranscriptMessages(event.data)
                val startIndex = cursor.resumeMessageIndex
                    ?.coerceAtMost((decoded?.lastIndex ?: -1))
                    ?: (decoded?.lastIndex ?: -1)
                val nextIndex = collectEventMessages(
                    decoded = decoded,
                    startIndex = startIndex,
                    suppressed = suppressed,
                    output = collectedNewestFirst,
                    limit = messageLimit,
                )
                if (collectedNewestFirst.size >= messageLimit) {
                    return pageResult(
                        collectedNewestFirst = collectedNewestFirst,
                        nextCursor = when {
                            nextIndex >= 0 -> LocalTranscriptPageCursor(
                                beforeSequenceExclusive = sequence,
                                resumeEventSequence = sequence,
                                resumeMessageIndex = nextIndex,
                                resumeActiveTranscript = cursor.resumeActiveTranscript,
                                suppressedMessageIds = suppressed.toSet(),
                            )
                            cursor.resumeActiveTranscript -> null
                            else -> LocalTranscriptPageCursor(
                                beforeSequenceExclusive = sequence,
                                suppressedMessageIds = suppressed.toSet(),
                            )
                        },
                    )
                }
                if (cursor.resumeActiveTranscript) {
                    return pageResult(collectedNewestFirst, nextCursor = null)
                }
                beforeSequenceExclusive = sequence
            } else {
                beforeSequenceExclusive = minOf(beforeSequenceExclusive, sequence)
            }
        }

        while (collectedNewestFirst.size < messageLimit) {
            val scanBefore = beforeSequenceExclusive
            val events = eventLog.pageBefore(
                sequenceExclusive = scanBefore,
                limit = scanPageSize,
            )
            if (events.isEmpty()) break

            for (event in events.asReversed()) {
                if (event.type == "assistant/message") {
                    replacedMessageId(event)?.let(suppressed::add)
                }

                val decoded = decodeTranscriptMessages(event.data)
                val activeTranscript = event.type == "chat/active-transcript" && decoded != null
                val nextIndex = collectEventMessages(
                    decoded = decoded,
                    startIndex = decoded?.lastIndex ?: -1,
                    suppressed = suppressed,
                    output = collectedNewestFirst,
                    limit = messageLimit,
                )

                if (collectedNewestFirst.size >= messageLimit) {
                    return pageResult(
                        collectedNewestFirst = collectedNewestFirst,
                        nextCursor = when {
                            nextIndex >= 0 -> LocalTranscriptPageCursor(
                                beforeSequenceExclusive = event.sequence,
                                resumeEventSequence = event.sequence,
                                resumeMessageIndex = nextIndex,
                                resumeActiveTranscript = activeTranscript,
                                suppressedMessageIds = suppressed.toSet(),
                            )
                            activeTranscript -> null
                            else -> LocalTranscriptPageCursor(
                                beforeSequenceExclusive = event.sequence,
                                suppressedMessageIds = suppressed.toSet(),
                            )
                        },
                    )
                }

                if (activeTranscript) {
                    return pageResult(collectedNewestFirst, nextCursor = null)
                }
            }

            val oldestSequence = events.first().sequence
            if (oldestSequence >= scanBefore) break
            beforeSequenceExclusive = oldestSequence
        }

        return pageResult(collectedNewestFirst, nextCursor = null)
    }

    private fun collectEventMessages(
        decoded: List<LocalHarnessMessage>?,
        startIndex: Int,
        suppressed: MutableSet<String>,
        output: MutableList<LocalHarnessMessage>,
        limit: Int,
    ): Int {
        if (decoded.isNullOrEmpty() || startIndex < 0) return -1
        var index = startIndex
        while (index >= 0 && output.size < limit) {
            val message = decoded[index]
            if (!suppressed.remove(message.id)) {
                output += message
            }
            index -= 1
        }
        return index
    }

    private fun pageResult(
        collectedNewestFirst: List<LocalHarnessMessage>,
        nextCursor: LocalTranscriptPageCursor?,
    ): LocalTranscriptPage = LocalTranscriptPage(
        messages = collectedNewestFirst.asReversed(),
        nextCursor = nextCursor,
    )

    private fun replacedMessageId(event: LocalSessionEventLog.Event): String? =
        (event.data["replaces"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.takeIf(String::isNotBlank)

    private companion object {
        const val DEFAULT_EVENT_PAGE_SIZE = 80
        const val MAX_EVENT_PAGE_SIZE = 512
        const val DEFAULT_MESSAGE_PAGE_SIZE = 200
        const val MAX_MESSAGE_PAGE_SIZE = 1_000
    }
}

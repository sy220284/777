package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatContinuitySanitizerTest {
    private val raw = Json.parseToJsonElement(
        """{"decisions":[],"unfinished":[],"recentEvents":[]}""",
    ).jsonObject

    @Test
    fun activeEventsAreNotTruncatedBeforeEvidenceReconciliation() {
        val planned = (1..32).map { "决定事项$it" }
        val open = (1..36).map { "待续事项$it" }
        val next = sanitizeChatContinuity(
            value = ChatContinuityState(decisions = planned, unfinished = open),
            previous = ChatContinuityState(),
            raw = raw,
        )
        assertEquals(planned, next.decisions)
        assertEquals(open, next.unfinished)
    }

    @Test
    fun omittedPatchPreservesEveryOldActiveEvent() {
        val previous = ChatContinuityState(
            decisions = (1..27).map { "决定事项$it" },
            unfinished = (1..23).map { "待续事项$it" },
            recentEvents = (1..8).map { "近期事件$it" },
        )
        val next = sanitizeChatContinuity(
            value = ChatContinuityState(), previous = previous,
            raw = Json.parseToJsonElement("{}").jsonObject,
        )
        assertEquals(previous.decisions, next.decisions)
        assertEquals(previous.unfinished, next.unfinished)
        assertEquals(previous.recentEvents.takeLast(5), next.recentEvents)
    }
}

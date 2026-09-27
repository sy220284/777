package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSceneRuntimeTest {
    @Test
    fun mentionOnlyDoesNotCreateLocationEvent() {
        val previous = ChatSceneState(location = "院子")

        val events = ChatSceneRuntime.extractTurnEvents(
            previous = previous,
            userMessage = "你还记得那间卧室吗？",
            assistantMessage = "记得，我们继续说刚才的事。",
            sequence = 12L,
        )

        assertTrue(events.none { it.kind == ChatSceneEventKind.LOCATION })
        assertEquals("院子", ChatSceneRuntime.reduce(previous, events).location)
    }

    @Test
    fun explicitCompletedMovementAdvancesHardLocation() {
        val previous = ChatSceneState(location = "院子")

        val events = ChatSceneRuntime.extractTurnEvents(
            previous = previous,
            userMessage = "进去说吧。",
            assistantMessage = "她起身推开门，和你一起走进房间。",
            sequence = 13L,
        )
        val next = ChatSceneRuntime.reduce(previous, events)

        assertTrue(events.any { it.kind == ChatSceneEventKind.LOCATION })
        assertEquals("房间", next.location)
    }

    @Test
    fun barePresenceAtAnotherPlaceIsRejectedBeforeCommit() {
        val check = ChatSceneRuntime.inspectReply(
            previous = ChatSceneState(location = "院子"),
            userMessage = "继续。",
            assistantMessage = "她坐在卧室床边，抬眼看你：刚才说到哪了？",
        )

        assertFalse(check.accepted)
        assertTrue(check.violations.any { it.code == "UNBRIDGED_LOCATION_CHANGE" })
    }

    @Test
    fun replyMayMoveAndThenActAtDestination() {
        val check = ChatSceneRuntime.inspectReply(
            previous = ChatSceneState(location = "院子"),
            userMessage = "进去说吧。",
            assistantMessage = "她拉着你走进房间，在房间床边坐下：现在说吧。",
        )

        assertTrue(check.accepted)
        assertTrue(check.events.any {
            it.kind == ChatSceneEventKind.LOCATION && it.to.contains("房间")
        })
    }

    @Test
    fun futurePlanDoesNotAdvanceTimeButNarrativeCutDoes() {
        val previous = ChatSceneState(sceneTime = "夜晚", location = "院子")

        val planned = ChatSceneRuntime.extractTurnEvents(
            previous = previous,
            userMessage = "明天早上再说吧。",
            assistantMessage = "好，今晚先到这里。",
            sequence = 20L,
        )
        assertEquals("夜晚", ChatSceneRuntime.reduce(previous, planned).sceneTime)

        val jumped = ChatSceneRuntime.extractTurnEvents(
            previous = previous,
            userMessage = "继续。",
            assistantMessage = "第二天早上，她推开院门走进房间。",
            sequence = 21L,
        )
        val next = ChatSceneRuntime.reduce(previous, jumped)
        assertTrue(next.sceneTime.startsWith("第二天"))
        assertEquals("房间", next.location)
    }

    @Test
    fun movementTransitionSuffixIsNotPartOfLocation() {
        val events = ChatSceneRuntime.extractTurnEvents(
            previous = ChatSceneState(location = "院子"),
            userMessage = "继续。",
            assistantMessage = "她回到房间后，顺手关上了门。",
            sequence = 25L,
        )

        assertEquals(
            "房间",
            ChatSceneRuntime.reduce(ChatSceneState(location = "院子"), events).location,
        )
    }

    @Test
    fun locationEndingWithNeiKeepsItsCanonicalName() {
        val events = ChatSceneRuntime.extractTurnEvents(
            previous = ChatSceneState(location = ""),
            userMessage = "继续。",
            assistantMessage = "她此刻待在屋内，安静地看着窗外。",
            sequence = 26L,
        )

        assertEquals(
            "屋内",
            ChatSceneRuntime.reduce(ChatSceneState(), events).location,
        )
    }

    @Test
    fun applyingSameDurableTurnTwiceIsIdempotent() {
        val context = ChatContextState(scene = ChatSceneState(location = "院子"))
        val once = context.applySceneTurn(
            userMessage = "进去吧。",
            assistantMessage = "她和你一起走进房间。",
            sequence = 30L,
        )
        val twice = once.applySceneTurn(
            userMessage = "进去吧。",
            assistantMessage = "她和你一起走进房间。",
            sequence = 30L,
        )

        assertEquals("房间", twice.scene.location)
        assertEquals(once.sceneEvents, twice.sceneEvents)
    }
}

package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.runBlocking
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
    fun hardTransitionDropsLegacySoftSceneDetails() {
        val previous = ChatSceneState(
            location = "院子",
            participants = listOf("用户", "阿青"),
            positions = listOf("阿青靠着院墙"),
            activeActions = listOf("喝茶"),
            keyObjects = listOf("石桌"),
        )

        val events = ChatSceneRuntime.extractTurnEvents(
            previous = previous,
            userMessage = "进去吧。",
            assistantMessage = "她和你一起走进房间。",
            sequence = 29L,
        )
        val next = ChatSceneRuntime.reduce(previous, events)

        assertEquals("房间", next.location)
        assertTrue(next.participants.isEmpty())
        assertTrue(next.positions.isEmpty())
        assertTrue(next.activeActions.isEmpty())
        assertTrue(next.keyObjects.isEmpty())
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

    @Test
    fun threeHundredTurnDialogueKeepsLatestHardSceneWithoutFillerDrift() {
        var context = ChatContextState()
        repeat(300) { zeroBased ->
            val turn = zeroBased + 1
            val user = when (turn) {
                120 -> "我们回到河岸旧书店吧。"
                180 -> "第二天早上，我们来到海边民宿。"
                else -> "第${turn}轮，继续聊眼前的日常。"
            }
            val assistant = when (turn) {
                120 -> "好，我们回到河岸旧书店继续聊。"
                180 -> "第二天早上，她和你一起走进海边民宿。"
                else -> "第${turn}轮回应，不移动场景，也不推进时间。"
            }
            context = context.applySceneTurn(
                userMessage = user,
                assistantMessage = assistant,
                sequence = turn.toLong(),
            )
        }

        assertEquals("海边民宿", context.scene.location)
        assertTrue(context.scene.sceneTime.startsWith("第二天"))
        assertTrue(context.sceneEvents.size <= 12)
    }

    @Test
    fun imaginedQuotedAndRememberedLocationsDoNotRewriteRealScene() {
        val scene = ChatSceneState(location = "院子")
        val samples = listOf(
            "如果我现在坐在卧室床边，也许会好好想一想。",
            "昨晚我梦见自己坐在海边的咖啡馆。",
            "我想象自己走进一间古老的书房。",
            "她想起纸条上写着“我现在坐在卧室床边”。",
            "她说“我现在坐在卧室床边”，然后自己笑了。",
        )
        samples.forEach { reply ->
            val inspected = ChatSceneRuntime.inspectReply(scene, "继续聊聊", reply)
            assertTrue("Should not rewrite scene for: $reply", inspected.accepted)
            val events = ChatSceneRuntime.extractTurnEvents(scene, "继续聊聊", reply, 17L)
            assertFalse("Imagined movement is not a durable scene: $reply",
                events.any { it.kind == ChatSceneEventKind.LOCATION })
        }
        assertFalse(ChatSceneRuntime.inspectReply(
            scene, "继续聊聊", "她现在坐在卧室床边看着你。",
        ).accepted)
    }

    @Test
    fun continuityGuardRetriesOnceAndReturnsRepairedReply() = runBlocking {
        var retries = 0
        val result = ChatReplyContinuityGuard.enforce(
            previous = ChatSceneState(location = "院子"),
            userMessage = "继续。",
            initial = "她坐在卧室床边看着你。",
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { it },
            retry = {
                retries += 1
                "她仍站在院子里，顺着刚才的话继续说下去。"
            },
        )

        assertEquals(1, retries)
        assertEquals("她仍站在院子里，顺着刚才的话继续说下去。", result)
    }

    @Test
    fun continuityGuardAllowsReplyWithLoggedWarningAfterSecondSceneJump() = runBlocking {
        val actions = mutableListOf<String>()
        val result = ChatReplyContinuityGuard.enforce(
            previous = ChatSceneState(location = "院子"),
            userMessage = "继续。",
            initial = "她坐在卧室床边看着你。",
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { it },
            retry = { "她躺在书房沙发上继续说。" },
            onEvent = { action, _ -> actions += action },
        )

        assertEquals("她坐在卧室床边看着你。", result)
        assertTrue(actions.any { it.contains("accepted-with-warning") })
    }

    @Test
    fun continuityGuardRepairRequestFailurePreservesCompletedReply() = runBlocking {
        val original = "她坐在卧室床边看着你。"
        val result = ChatReplyContinuityGuard.enforce(
            previous = ChatSceneState(location = "院子"),
            userMessage = "继续。",
            initial = original,
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { it },
            retry = { throw IllegalStateException("临时网络断开") },
        )
        assertEquals(original, result)
    }

    @Test
    fun arrivingBeforeSpeakingIsNotAPlaceAndRealNextMovementStillWorks() {
        val previous = ChatSceneState(location = "房间")
        val noMove = ChatSceneRuntime.extractTurnEvents(
            previous, "不能你到了才讲，我会不安的。", "", 40L,
        )
        assertTrue(noMove.none { it.kind == ChatSceneEventKind.LOCATION })
        assertEquals("房间", ChatSceneRuntime.reduce(previous, noMove).location)

        val moved = ChatSceneRuntime.extractTurnEvents(
            previous, "你到了才讲，然后我们走进茶室。", "", 41L,
        )
        assertEquals("茶室", ChatSceneRuntime.reduce(previous, moved).location)
    }

    @Test
    fun sceneTracksLatestCompletedMovementInsteadOfRegexDeclarationOrder() {
        val previous = ChatSceneState(location = "院子")
        val events = ChatSceneRuntime.extractTurnEvents(
            previous = previous, userMessage = "",
            assistantMessage = "她回到客厅后，走进卧室。",
            sequence = 71L,
        )
        assertEquals("卧室", ChatSceneRuntime.reduce(previous, events).location)
    }

    @Test
    fun conditionalAndFutureTravelDoNotOverwriteActualScene() {
        val previous = ChatSceneState(location = "院子")
        val events = ChatSceneRuntime.extractTurnEvents(
            previous = previous, userMessage = "",
            assistantMessage = "她计划明天走进房间。如果你来到花园，我们再慢慢谈。",
            sequence = 72L,
        )
        assertEquals("院子", ChatSceneRuntime.reduce(previous, events).location)
        assertTrue(events.none { it.kind == ChatSceneEventKind.LOCATION })
    }

}

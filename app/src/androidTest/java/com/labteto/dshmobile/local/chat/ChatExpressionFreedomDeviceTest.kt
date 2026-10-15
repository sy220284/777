package com.labteto.dshmobile.local.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side regression for chat expression freedom and group routing. */
@RunWith(AndroidJUnit4::class)
class ChatExpressionFreedomDeviceTest {
    @Test
    fun imaginedSceneDoesNotOverwriteARealLocation() {
        val scene = ChatSceneState(location = "院子")
        val imaginary = "她想象自己走进书房，又想起朋友说过“我坐在卧室床边”。"
        assertTrue(ChatSceneRuntime.inspectReply(scene, "继续", imaginary).accepted)
        val events = ChatSceneRuntime.extractTurnEvents(scene, "继续", imaginary, 1L)
        assertFalse(events.any { it.kind == ChatSceneEventKind.LOCATION })

        val actual = ChatSceneRuntime.inspectReply(
            scene, "继续", "她现在坐在卧室床边。")
        assertFalse(actual.accepted)
    }

    @Test
    fun groupCollectiveQuestionUsesLastSpeakerAndRotatingSecond() {
        val a = LocalGroupChatMember("a", "a", "阿青")
        val b = LocalGroupChatMember("b", "b", "小岚")
        val c = LocalGroupChatMember("c", "c", "阿树")
        assertEquals(
            listOf("c", "a"),
            groupChatResponders("大家说说", listOf(a, b, c), "c")
                .map(LocalGroupChatMember::galleryId),
        )
        assertEquals(
            listOf("b"),
            groupChatResponders("@小岚 你怎么看", listOf(a, b, c), "c")
                .map(LocalGroupChatMember::galleryId),
        )
        assertEquals(
            listOf("a", "b", "c"),
            groupChatResponders("每个人都回答", listOf(a, b, c), "c")
                .map(LocalGroupChatMember::galleryId),
        )
    }
}

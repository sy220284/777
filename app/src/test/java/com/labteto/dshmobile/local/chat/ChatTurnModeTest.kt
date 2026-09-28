package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnModeTest {
    @Test
    fun shortContinuationKeepsCurrentBeat() {
        assertEquals(ChatTurnMode.CONTINUE_BEAT, inferChatTurnMode("继续"))
        assertEquals(ChatTurnMode.CONTINUE_BEAT, inferChatTurnMode("然后呢"))
        val prompt = renderChatTurnModeForModel("继续")
        assertTrue(prompt.contains("继续当前节拍"))
        assertTrue(prompt.contains("不为“推进”凭空换场"))
    }

    @Test
    fun callbackTransitionAndResetAreSeparated() {
        assertEquals(ChatTurnMode.CALLBACK, inferChatTurnMode("你还记得我们第一次见面吗"))
        assertEquals(ChatTurnMode.SCENE_TRANSITION, inferChatTurnMode("我们去外面说吧"))
        assertEquals(ChatTurnMode.RESET, inferChatTurnMode("换个话题，说正事"))
    }

    @Test
    fun followUpDoesNotOpenNewPlot() {
        assertEquals(ChatTurnMode.FOLLOW_UP, inferChatTurnMode("为什么呢"))
        assertTrue(renderChatTurnModeForModel("为什么呢").contains("不额外开启新剧情"))
    }

    @Test
    fun negatedMovementDoesNotForceSceneTransition() {
        assertEquals(ChatTurnMode.NORMAL, inferChatTurnMode("我不想去外面"))
        assertEquals(ChatTurnMode.NORMAL, inferChatTurnMode("别出去，外面太冷了"))
        assertEquals(ChatTurnMode.SCENE_TRANSITION, inferChatTurnMode("我们去外面说吧"))
    }

    @Test
    fun resetWordDoesNotMatchInsideUnrelatedWord() {
        assertEquals(ChatTurnMode.NORMAL, inferChatTurnMode("预算了三万，接下来怎么安排"))
        assertEquals(ChatTurnMode.RESET, inferChatTurnMode("算了，换个话题"))
    }

}

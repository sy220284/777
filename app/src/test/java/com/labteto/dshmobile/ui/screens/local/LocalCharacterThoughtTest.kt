package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.extractLocalCharacterThought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCharacterThoughtTest {
    @Test fun stripsThoughtMarkerAndLimitsToTwentyCharacters() {
        val input = "【心声：她是不是又想试探我到底有没有记住昨天发生的事情呢】我记得。"
        val (thought, body) = extractLocalCharacterThought(input)
        assertEquals(20, thought?.length)
        assertEquals("我记得。", body)
    }

    @Test fun halfStreamHeaderNeverLeaksControlMarkers() {
        assertEquals(null to "", extractLocalCharacterThought("【心声：我有点", streaming = true))
    }

    @Test fun incrementalMarkerPrefixesStayHiddenOnlyDuringStreaming() {
        listOf("【", "【心", "【心声", "【心声：", "【心声:").forEach { prefix ->
            assertEquals(null to "", extractLocalCharacterThought(prefix, streaming = true))
            assertEquals(null to prefix, extractLocalCharacterThought(prefix))
        }
        assertEquals(null to "【你好】", extractLocalCharacterThought("【你好】", streaming = true))
    }

    @Test fun finalIncompleteHeaderDoesNotHideTheReply() {
        assertEquals(null to "【心声：我有点", extractLocalCharacterThought("【心声：我有点"))
    }

    @Test fun thoughtIsLimitedByCodePointsWithoutSplittingEmoji() {
        val reply = "【心声：" + "💭".repeat(28) + "】正文"
        val (thought, body) = extractLocalCharacterThought(reply)
        assertEquals(20, thought!!.codePointCount(0, thought.length))
        assertEquals("正文", body)
    }

    @Test fun groupSpeakerPrefixIsRemovedBeforeThoughtParsing() {
        val message = com.labteto.dshmobile.local.session.LocalHarnessMessage(
            id = "group-thought", role = "assistant", content = "小雨：【心声：有点期待】我来帮你。",
            createdAt = 1L, speakerName = "小雨",
        )
        val visible = com.labteto.dshmobile.local.presentation.groupMessageVisibleContent(message)
        assertEquals("有点期待" to "我来帮你。", extractLocalCharacterThought(visible))
    }

    @Test fun normalConversationIsNotModified() {
        assertEquals(null to "你好", extractLocalCharacterThought("你好"))
    }

    @Test fun invalidOrOversizedHeaderDoesNotHideTheReply() {
        val malformed = "【心声：" + "想".repeat(300) + "】正文"
        assertEquals(null to malformed, extractLocalCharacterThought(malformed))
        val unterminated = "【心声：" + "想".repeat(300)
        assertEquals(null to unterminated, extractLocalCharacterThought(unterminated))
    }
}

package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamFilterTest {
    @Test
    fun splitBlockedPhraseNeverLeaksAcrossChunks() {
        val filter = ChatStreamFilter(listOf("我理解你的感受"))
        val visible = buildString {
            append(filter.append("前面我理").text)
            append(filter.append("解你的").text)
            append(filter.append("感受后面").text)
            append(filter.flush().text)
        }

        assertEquals("前面后面", visible)
        assertFalse("我理解你的感受" in visible)
    }

    @Test
    fun safeTextStreamsBeforeFinalFlush() {
        val filter = ChatStreamFilter(listOf("禁用词"))
        val first = filter.append("这是一段安全回复，继续")
        val tail = filter.flush()

        assertTrue(first.text.isNotEmpty())
        assertEquals("这是一段安全回复，继续", first.text + tail.text)
    }

    @Test
    fun multipleConfiguredPhrasesAreRemovedLiterally() {
        val filter = ChatStreamFilter(listOf("套话", "机械表达"))
        val visible = buildString {
            append(filter.append("保留套").text)
            append(filter.append("话中间机械").text)
            append(filter.append("表达结尾").text)
            append(filter.flush().text)
        }

        assertEquals("保留中间结尾", visible)
    }
}

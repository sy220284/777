package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkOutputLimitProgressTest {
    @Test fun pureLongTextCanContinueMoreThanTwiceAndStopsOnRepeatedOrEmptyBody() {
        val history = mutableListOf<String>()
        repeat(12) { index ->
            val text = "新的正文段落 $index"
            assertTrue(hasNewOutputLimitText(history, text))
            history += text
        }
        assertFalse(hasNewOutputLimitText(history, ""))
        assertFalse(hasNewOutputLimitText(history, history.first()))
        assertFalse(hasNewOutputLimitText(history, "正文段落 1"))
    }
    @Test fun multimodalPredecessorUsesCanonicalTextProjection() {
        val predecessor = Json.parseToJsonElement("""{"role":"assistant","content":[{"type":"text","text":"上一段"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}""").jsonObject
        val previous = LocalCanonicalModelCodec.message(predecessor).content
            .filterIsInstance<LocalCanonicalContent.Text>().joinToString("") { it.text }
        assertTrue(hasNewOutputLimitText(listOf(previous), "新的段落"))
        assertFalse(hasNewOutputLimitText(listOf(previous), "上一段"))
    }
}

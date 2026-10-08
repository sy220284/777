package com.labteto.dshmobile.ui.screens.local

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
        assertEquals(null to "", extractLocalCharacterThought("【心声：我有点"))
    }

    @Test fun normalConversationIsNotModified() {
        assertEquals(null to "你好", extractLocalCharacterThought("你好"))
    }
}

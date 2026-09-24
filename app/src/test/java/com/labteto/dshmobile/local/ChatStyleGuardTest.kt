package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStyleGuardTest {
    @Test fun detectsAndScrubsBlacklistedPhrases() {
        val text = "我理解你的感受。如果你愿意的话，我们可以继续聊。"
        val violations = ChatStyleGuard.violations(text)

        assertTrue("我理解你的感受" in violations)
        assertTrue("如果你愿意的话" in violations)

        val scrubbed = ChatStyleGuard.scrub(text)
        assertFalse(ChatStyleGuard.bannedPhrases.any { it in scrubbed })
    }

    @Test fun naturalChatPassesUntouched() {
        val text = "……你还知道回来？"
        assertTrue(ChatStyleGuard.violations(text).isEmpty())
        assertEquals(text, ChatStyleGuard.scrub(text))
    }
}

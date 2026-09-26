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

    @Test fun builtInScrubDropsWholeBadSentenceInsteadOfLeavingFragments() {
        val scrubbed = ChatStyleGuard.scrub("听起来你今天很开心。那就笑一个。")
        assertEquals("那就笑一个。", scrubbed)
        assertFalse("今天很开心" in scrubbed)
    }

    @Test fun personaSpecificBanIsAlsoEnforced() {
        val text = "嗯。亲爱的，你终于回来了。"
        val extra = listOf("亲爱的")
        assertEquals(listOf("亲爱的"), ChatStyleGuard.violations(text, extra))
        val scrubbed = ChatStyleGuard.scrub(text, extra)
        assertFalse("亲爱的" in scrubbed)
        assertEquals("嗯。你终于回来了。", scrubbed)
    }

    @Test fun disablingGenericGuardStillEnforcesPersonaBan() {
        val text = "我理解你的感受。亲爱的，回来就好。"
        assertEquals(
            listOf("亲爱的"),
            ChatStyleGuard.violations(
                text,
                extraBannedPhrases = listOf("亲爱的"),
                builtInEnabled = false,
            ),
        )
        val scrubbed = ChatStyleGuard.scrub(
            text,
            extraBannedPhrases = listOf("亲爱的"),
            builtInEnabled = false,
        )
        assertTrue("我理解你的感受" in scrubbed)
        assertFalse("亲爱的" in scrubbed)
    }

    @Test fun naturalChatPassesUntouched() {
        val text = "……你还知道回来？"
        assertTrue(ChatStyleGuard.violations(text).isEmpty())
        assertEquals(text, ChatStyleGuard.scrub(text))
    }
    @Test fun masterSwitchCanDisableEveryOutputFilter() {
        val phrases = ChatStyleGuard.activePhrases(
            customPhrases = listOf("自定义词"),
            personaPhrases = listOf("角色禁用词"),
            enabled = false,
        )
        assertTrue(phrases.isEmpty())
    }

    @Test fun activePhraseSetCombinesBuiltInCustomAndPersonaTerms() {
        val phrases = ChatStyleGuard.activePhrases(
            customPhrases = listOf("自定义词"),
            personaPhrases = listOf("角色禁用词"),
            enabled = true,
        )
        assertTrue("我理解你的感受" in phrases)
        assertTrue("自定义词" in phrases)
        assertTrue("角色禁用词" in phrases)
    }

    @Test fun literalFilterMatchesStreamingSemantics() {
        val filtered = ChatStyleGuard.filterLiteral(
            "开头我理解你的感受中间自定义词结尾",
            listOf("我理解你的感受", "自定义词"),
        )
        assertEquals("开头中间结尾", filtered)
    }

}

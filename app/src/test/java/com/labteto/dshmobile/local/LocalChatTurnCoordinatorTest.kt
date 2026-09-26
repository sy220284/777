package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.PersonaProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatTurnCoordinatorTest {
    @Test
    fun groupAntiRepeatOnlyLooksAtTheSameCharacter() {
        val messages = listOf(
            LocalHarnessMessage("a1", "assistant", "甲的旧话", createdAt = 1L, speakerId = "a", speakerName = "甲"),
            LocalHarnessMessage("b1", "assistant", "乙的旧话", createdAt = 2L, speakerId = "b", speakerName = "乙"),
            LocalHarnessMessage("a2", "assistant", "甲的新话", createdAt = 3L, speakerId = "a", speakerName = "甲"),
        )

        val replies = recentRoleReplies(
            messages = messages,
            groupEnabled = true,
            persona = PersonaProfile(id = "a", name = "甲"),
        )

        assertEquals(listOf("甲的旧话", "甲的新话"), replies)
    }
}

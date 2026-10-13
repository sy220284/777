package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGroupPendingRecoveryTest {
    private val members = listOf(
        LocalGroupChatMember("alpha", "a", "阿青"),
        LocalGroupChatMember("beta", "b", "小白"),
    )

    @Test
    fun durableSpeakerIdentityWinsAndRepeatedMemberUsesLatestTurn() {
        val pending = listOf(
            ChatPendingTurn(sequence = 10L, assistantMessageId = "a10",
                assistantMessage = "阿青第一次", speakerId = "alpha"),
            ChatPendingTurn(sequence = 11L, assistantMessageId = "b11",
                assistantMessage = "小白发言", speakerId = "beta"),
            ChatPendingTurn(sequence = 12L, assistantMessageId = "a12",
                assistantMessage = "阿青第二次", speakerId = "alpha"),
        )
        val selected = pendingGroupSpeakers(pending, members, emptyList())!!
        assertEquals(listOf("beta", "alpha"), selected.map { it.first.galleryId })
        assertEquals(listOf(11L, 12L), selected.map { it.second.sequence })
    }

    @Test
    fun legacyPendingCanUseTranscriptButNeverAssignAnUnknownSpeaker() {
        val legacy = ChatPendingTurn(sequence = 14L, assistantMessageId = "a14",
            assistantMessage = "旧群聊", speakerId = "")
        val transcript = listOf(
            LocalHarnessMessage("a14", "assistant", "旧群聊",
                createdAt = 14L, speakerId = "beta"),
        )
        assertEquals("beta",
            pendingGroupSpeakers(listOf(legacy), members, transcript)!!.single().first.galleryId)
        assertNull(pendingGroupSpeakers(listOf(legacy), members, emptyList()))
        assertNull(pendingGroupSpeakers(
            listOf(legacy), members,
            listOf(LocalHarnessMessage("a14", "assistant", "旧群聊",
                createdAt = 14L, speakerId = "nonmember")),
        ))
    }

    @Test
    fun memberMergePreservesPrivateIdentityAndOnlyUpdatesTheActualResponder() {
        val updated = ChatCharacterState(
            mood = "开心",
            scene = ChatSceneState(location = "测试旧投影"),
            continuity = ChatContinuityState(unfinished = listOf("测试旧待续")),
        )
        val replies = listOf(GroupReplyForStateUpdate(members.first(), PersonaProfile(), "好"))
        val merged = mergeRefreshedGroupMembers(
            members, mapOf("alpha" to updated), replies,
        )
        assertEquals("开心", merged[0].chatState.mood)
        assertTrue(merged[0].chatState.scene.location.isBlank())
        assertTrue(merged[0].chatState.continuity.unfinished.isEmpty())
        assertEquals(members[1], merged[1])
    }
}

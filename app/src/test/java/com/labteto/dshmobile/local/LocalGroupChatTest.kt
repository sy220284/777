package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupChatTest {
    private val ayaka = LocalGroupChatMember(
        galleryId = "ayaka",
        personaId = "ayaka",
        displayName = "神里绫华",
        chatState = ChatCharacterState(),
    )
    private val kafka = LocalGroupChatMember(
        galleryId = "kafka",
        personaId = "kafka",
        displayName = "卡芙卡",
        chatState = ChatCharacterState(),
    )
    private val zhao = LocalGroupChatMember(
        galleryId = "zhao",
        personaId = "zhao",
        displayName = "赵二",
        chatState = ChatCharacterState(),
    )

    @Test
    fun explicitMentionRoutesOnlyToNamedCharacter() {
        val responders = groupChatResponders(
            input = "@卡芙卡 你怎么看？",
            members = listOf(ayaka, kafka, zhao),
        )

        assertEquals(listOf("kafka"), responders.map { it.galleryId })
    }

    @Test
    fun ordinaryMessageKeepsAllCharactersEligibleInGivenPriorityOrder() {
        val responders = groupChatResponders(
            input = "你们今天怎么都这么安静",
            members = listOf(zhao, ayaka, kafka),
        )

        assertEquals(listOf("zhao", "ayaka", "kafka"), responders.map { it.galleryId })
    }

    @Test
    fun transcriptLineKeepsSpeakerVisibleToTheNextAgent() {
        val message = LocalHarnessMessage(
            id = "a1",
            role = "assistant",
            content = "这件事我另有看法。",
            createdAt = 1L,
            speakerId = kafka.galleryId,
            speakerName = kafka.displayName,
        )

        assertEquals("卡芙卡：这件事我另有看法。", groupTranscriptLine(message))
    }

    @Test
    fun groupStateDefaultsToSingleAndNeedsExplicitGroupMode() {
        assertTrue(!LocalGroupChatState().enabled)
        assertTrue(LocalGroupChatState(mode = LocalChatMode.GROUP).enabled)
    }
}

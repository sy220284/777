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
    fun naturalInSentenceAddressPrefersTheActuallyAddressedCharacter() {
        val responders = groupChatResponders(
            input = "卡芙卡说得有道理，神里绫华你怎么看？",
            members = listOf(ayaka, kafka, zhao),
        )

        assertEquals(listOf("ayaka"), responders.map { it.galleryId })
    }

    @Test
    fun multipleNaturalAddressesKeepMentionOrder() {
        val responders = groupChatResponders(
            input = "神里绫华、卡芙卡，你们两个都说说。",
            members = listOf(kafka, ayaka, zhao),
        )

        assertEquals(listOf("ayaka", "kafka"), responders.map { it.galleryId })
    }

    @Test
    fun ordinaryMessageUsesOneRotatedPrimarySpeaker() {
        val responders = groupChatResponders(
            input = "今天怎么这么安静",
            members = listOf(zhao, ayaka, kafka),
        )

        assertEquals(listOf("zhao"), responders.map { it.galleryId })
    }

    @Test
    fun collectiveMessageAllowsTwoNaturalRespondersWithoutQueueingEveryone() {
        val extra = LocalGroupChatMember(
            galleryId = "extra",
            personaId = "extra",
            displayName = "黎深",
            chatState = ChatCharacterState(),
        )
        val responders = groupChatResponders(
            input = "大家随便聊聊",
            members = listOf(ayaka, kafka, zhao, extra),
        )

        assertEquals(MAX_GROUP_CHAT_RESPONDERS_PER_TURN, responders.size)
        assertEquals(listOf("ayaka", "kafka"), responders.map { it.galleryId })
    }

    @Test
    fun referentialNameMentionDoesNotForceThatCharacterToReply() {
        val responders = groupChatResponders(
            input = "刚才卡芙卡说得那句挺有意思",
            members = listOf(ayaka, kafka, zhao),
        )

        assertEquals(listOf("ayaka"), responders.map { it.galleryId })
    }

    @Test
    fun strongerDirectAddressWinsOverReferencedNameWithComma() {
        val responders = groupChatResponders(
            input = "我刚提到卡芙卡，神里绫华你怎么看？",
            members = listOf(ayaka, kafka, zhao),
        )

        assertEquals(listOf("ayaka"), responders.map { it.galleryId })
    }

    @Test
    fun naturalFindPhraseRoutesOnlyToRequestedCharacter() {
        val responders = groupChatResponders(
            input = "我想找卡芙卡聊聊",
            members = listOf(ayaka, kafka, zhao),
        )

        assertEquals(listOf("kafka"), responders.map { it.galleryId })
    }

    @Test
    fun characterNameAloneRoutesOnlyToThatCharacter() {
        val responders = groupChatResponders(
            input = "赵二",
            members = listOf(ayaka, kafka, zhao),
        )

        assertEquals(listOf("zhao"), responders.map { it.galleryId })
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

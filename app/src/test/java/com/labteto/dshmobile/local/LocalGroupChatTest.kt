package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.chat.MAX_GROUP_CHAT_RESPONDERS_PER_TURN
import com.labteto.dshmobile.local.chat.groupChatResponders
import com.labteto.dshmobile.local.chat.groupMemberMemoryQuery
import com.labteto.dshmobile.local.chat.groupMemberMayStaySilent
import com.labteto.dshmobile.local.chat.groupMessageVisibleContent
import com.labteto.dshmobile.local.chat.groupTranscriptLine
import com.labteto.dshmobile.local.chat.migrateLegacyConversationContext
import com.labteto.dshmobile.local.chat.stripGroupSpeakerPrefix
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.json.Json
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
    fun groupFollowUpMemoryUsesOnlyDeliveredSpeechAndKeepsItBounded() {
        val first = groupMemberMemoryQuery("你们觉得怎么样？", emptyList())
        assertEquals("你们觉得怎么样？", first)
        val expanded = groupMemberMemoryQuery(
            "你们觉得怎么样？",
            listOf("阿青：我想起一起去海边的那次约定", "卡芙卡：那天我们还见过灯塔"),
        )
        assertTrue(expanded.contains("一起去海边的那次约定"))
        assertTrue(expanded.contains("那天我们还见过灯塔"))
        val many = groupMemberMemoryQuery(
            "新问题",
            listOf("旧发言不应保留", "第二位：车站见面", "第三位：失而复得的地图"),
        )
        assertTrue(!many.contains("旧发言不应保留"))
        assertTrue(many.contains("车站见面"))
        assertTrue(many.contains("地图"))
        assertTrue(groupMemberMemoryQuery("x".repeat(900), listOf("话".repeat(900))).length <= 640)
    }

    @Test
    fun optionalGroupSilenceNeverOverridesExplicitlyRequestedSpeakers() {
        assertTrue(!groupMemberMayStaySilent("大家聊聊今天的事", ayaka, 0))
        assertTrue(groupMemberMayStaySilent("大家聊聊今天的事", kafka, 1))
        assertTrue(!groupMemberMayStaySilent("每个人都回答：你们怎么看？", kafka, 1))
        assertTrue(!groupMemberMayStaySilent("卡芙卡，你怎么看？", kafka, 1))
    }

    @Test
    fun referencedCharacterCanRemainSilentButDirectedSpeakerMustReply() {
        val prompt = "大家觉得卡芙卡昨天的决定怎么样？"
        assertEquals(
            listOf("ayaka", "kafka"),
            groupChatResponders(prompt, listOf(ayaka, kafka, zhao))
                .map(LocalGroupChatMember::galleryId),
        )
        assertTrue(groupMemberMayStaySilent(prompt, kafka, 1))
        assertFalse(groupMemberMayStaySilent("@神里绫华，@卡芙卡，分别回答", kafka, 1))
        assertFalse(groupMemberMayStaySilent("卡芙卡，你怎么看？", kafka, 1))
        assertFalse(groupMemberMayStaySilent("每个人都回答", kafka, 1))
    }

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
    fun fourOrSixExplicitlyAddressedMembersAreAllHonored() {
        val extras = listOf(
            LocalGroupChatMember("other1", "other1", "云浅"),
            LocalGroupChatMember("other2", "other2", "陆璃"),
            LocalGroupChatMember("other3", "other3", "叶澜"),
        )
        val group = listOf(ayaka, kafka, zhao) + extras
        val four = groupChatResponders(
            "@神里绫华，@卡芙卡，@赵二，@云浅，四位都说说。",
            group,
        )
        assertEquals(listOf("ayaka", "kafka", "zhao", "other1"),
            four.map(LocalGroupChatMember::galleryId))
        val six = groupChatResponders(
            "@神里绫华，@卡芙卡，@赵二，@云浅，@陆璃，@叶澜，逐个回应。",
            group,
        )
        assertEquals(group.map(LocalGroupChatMember::galleryId),
            six.map(LocalGroupChatMember::galleryId))
        assertEquals(2, groupChatResponders("大家随便聊聊", group).size)
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
    fun explicitlyAskingEveryoneInvitesEveryMemberInRosterOrder() {
        val extra = LocalGroupChatMember(
            galleryId = "extra", personaId = "extra",
            displayName = "黎深", chatState = ChatCharacterState(),
        )
        val responders = groupChatResponders(
            input = "每个人都回答：你们各自怎么看这件事？",
            members = listOf(ayaka, kafka, zhao, extra),
        )
        assertEquals(listOf("ayaka", "kafka", "zhao", "extra"),
            responders.map { it.galleryId })
        assertEquals(listOf("ayaka", "kafka"), groupChatResponders(
            "大家随便聊聊", listOf(ayaka, kafka, zhao, extra),
        ).map { it.galleryId })
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
    fun askingAboutCharacterDoesNotAddressThatCharacter() {
        val responders = groupChatResponders(
            input = "关于卡芙卡怎么看？",
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
    fun duplicateSpeakerPrefixIsRemovedBeforeDisplayAndHistory() {
        val message = LocalHarnessMessage(
            id = "a2",
            role = "assistant",
            content = "赵二：这事交给我。",
            createdAt = 2L,
            speakerId = zhao.galleryId,
            speakerName = zhao.displayName,
        )

        assertEquals("这事交给我。", groupMessageVisibleContent(message))
        assertEquals("赵二：这事交给我。", groupTranscriptLine(message))
    }

    @Test
    fun speakerPrefixNormalizerSupportsCommonModelFormats() {
        assertEquals("过来。", stripGroupSpeakerPrefix("赵二: 过来。", "赵二"))
        assertEquals("过来。", stripGroupSpeakerPrefix("**赵二：** 过来。", "赵二"))
        assertEquals("过来。", stripGroupSpeakerPrefix("【赵二】\n过来。", "赵二"))
        assertEquals("过来。", stripGroupSpeakerPrefix("【赵二】：过来。", "赵二"))
        assertEquals("过来。", stripGroupSpeakerPrefix("@赵二：过来。", "赵二"))
        assertEquals("赵二今天心情不错。", stripGroupSpeakerPrefix("赵二今天心情不错。", "赵二"))
    }

    @Test
    fun groupStateDefaultsToSingleAndNeedsExplicitGroupMode() {
        assertTrue(!LocalGroupChatState().enabled)
        assertTrue(LocalGroupChatState(mode = LocalChatMode.GROUP).enabled)
    }

    @Test
    fun announcementSurvivesSessionSerializationAndOldGroupStatesStillLoad() {
        val old = Json.decodeFromString<LocalGroupChatState>("""{"mode":"GROUP"}""")
        assertEquals("", old.announcement)

        val saved = old.copy(announcement = "众人被同一封邀请函叫到现场。")
        val restored = Json.decodeFromString<LocalGroupChatState>(
            Json.encodeToString(LocalGroupChatState.serializer(), saved),
        )
        assertEquals(saved.announcement, restored.announcement)
        assertTrue(restored.enabled)
    }
    @Test
    fun legacyGroupMemberContextMigratesOnceIntoSharedContext() {
        val legacyMember = ayaka.copy(
            chatState = ChatCharacterState(
                mood = "开心",
                scene = ChatSceneState(sceneTime = "夜晚", location = "庭院"),
                continuity = ChatContinuityState(
                    recentEvents = listOf("刚刚一起吃完晚饭"),
                    decisions = listOf("今晚留在庭院"),
                ),
            ),
        )
        val migrated = LocalGroupChatState(
            mode = LocalChatMode.GROUP,
            members = listOf(legacyMember, kafka),
        ).migrateLegacyConversationContext()

        assertEquals("庭院", migrated.context.scene.location)
        assertEquals("夜晚", migrated.context.scene.sceneTime)
        assertEquals(listOf("刚刚一起吃完晚饭"), migrated.context.continuity.recentEvents)
        assertEquals(listOf("今晚留在庭院"), migrated.context.continuity.decisions)
        assertTrue(migrated.members.all { it.chatState.scene.location.isBlank() })
        assertTrue(migrated.members.all { it.chatState.continuity.recentEvents.isEmpty() })
        assertEquals("开心", migrated.members.first().chatState.mood)
    }

    @Test
    fun failedReplyMembersSurviveSessionSerializationAndOldStatesDefaultEmpty() {
        val saved = LocalGroupChatState(failedReplyMemberIds = listOf("member-a"))
        assertEquals(saved, Json.decodeFromString<LocalGroupChatState>(
            Json.encodeToString(LocalGroupChatState.serializer(), saved)))
        assertTrue(Json.decodeFromString<LocalGroupChatState>("""{"mode":"GROUP"}""").failedReplyMemberIds.isEmpty())
    }
}

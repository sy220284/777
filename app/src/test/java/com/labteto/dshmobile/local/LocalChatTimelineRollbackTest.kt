package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.chat.restoreChatStateBefore
import com.labteto.dshmobile.local.chat.restoreGroupStateBefore
import com.labteto.dshmobile.local.chat.sourceEventSequenceForMessage
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChatTimelineRollbackTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun restoresExactStateCheckpointBeforeUserMessage() {
        val expected = ChatCharacterState(
            mood = "期待",
            relationshipState = "很熟",
            currentFocus = "今晚去看灯",
            updatedAt = 10L,
        )
        val user = message("u1", "第一句", 20L)
        val events = listOf(
            baseline(1L, expected),
            transcriptEvent(2L, "user/message", user),
        )

        assertEquals(
            expected,
            restoreChatStateBefore(events.asSequence(), json, 2L, user.createdAt),
        )
        assertEquals(
            2L,
            sourceEventSequenceForMessage(events.asSequence(), user.id),
        )
    }

    @Test
    fun laterUserCheckpointWinsOverOlderPostTurnState() {
        val initial = ChatCharacterState(mood = "平静", relationshipState = "初识", updatedAt = 1L)
        val afterFirstTurn = ChatCharacterState(
            mood = "开心",
            relationshipState = "熟悉",
            currentFocus = "继续聊天",
            updatedAt = 3L,
        )
        val secondUser = message("u2", "第二句", 5L)
        val events = listOf(
            baseline(1L, initial),
            transcriptEvent(2L, "user/message", message("u1", "第一句", 2L)),
            LocalSessionEventLog.Event(
                sequence = 3L,
                type = "chat/post-turn",
                createdAt = 3L,
                data = buildJsonObject {
                    put("status", "updated")
                    put("state", json.encodeToJsonElement(ChatCharacterState.serializer(), afterFirstTurn))
                },
            ),
            baseline(4L, afterFirstTurn),
            transcriptEvent(5L, "user/message", secondUser),
        )

        assertEquals(
            afterFirstTurn,
            restoreChatStateBefore(events.asSequence(), json, 5L, secondUser.createdAt),
        )
    }

    @Test
    fun restoresGroupCheckpointBeforeEditedUserMessage() {
        val memberState = ChatCharacterState(
            mood = "放松",
            relationshipState = "群聊熟悉",
            updatedAt = 11L,
        )
        val group = LocalGroupChatState(
            mode = LocalChatMode.GROUP,
            members = listOf(
                LocalGroupChatMember(
                    galleryId = "gallery-a",
                    personaId = "persona-a",
                    displayName = "阿青",
                    chatState = memberState,
                ),
            ),
            turnCursor = 1,
            announcement = "雨夜客栈",
        )
        val user = message("u-group", "继续", 12L)
        val events = listOf(
            baseline(10L, ChatCharacterState(), group),
            transcriptEvent(11L, "user/message", user),
        )

        val restored = restoreGroupStateBefore(events.asSequence(), json, 11L, user.createdAt)

        assertNotNull(restored)
        assertEquals(group, restored)
        assertEquals(memberState, restored!!.members.single().chatState)
    }

    @Test
    fun pagedRollbackLookupCrossesMoreThanOneHistoryPage() {
        val log = LocalSessionEventLog(temporary.newFile("timeline.events.jsonl"), json)
        val expected = ChatCharacterState(
            mood = "安定",
            relationshipState = "熟悉",
            currentFocus = "旧回合边界",
            updatedAt = 10L,
        )
        log.append("chat/state-baseline", buildJsonObject {
            put("state", json.encodeToJsonElement(ChatCharacterState.serializer(), expected))
        })
        val user = message("u-paged", "很早的一句", 20L)
        val source = log.append("user/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(user)))
        })
        repeat(260) { index ->
            log.append("noise/event", buildJsonObject { put("index", index) })
        }

        assertEquals(source.sequence, sourceEventSequenceForMessage(log, user.id))
        assertEquals(
            expected,
            restoreChatStateBefore(log, json, source.sequence, user.createdAt),
        )
    }

    private fun baseline(
        sequence: Long,
        state: ChatCharacterState,
        group: LocalGroupChatState? = null,
    ) = LocalSessionEventLog.Event(
        sequence = sequence,
        type = "chat/state-baseline",
        createdAt = sequence,
        data = buildJsonObject {
            put("state", json.encodeToJsonElement(ChatCharacterState.serializer(), state))
            group?.let {
                put("group_state", json.encodeToJsonElement(LocalGroupChatState.serializer(), it))
            }
        },
    )

    private fun transcriptEvent(
        sequence: Long,
        type: String,
        message: LocalHarnessMessage,
    ) = LocalSessionEventLog.Event(
        sequence = sequence,
        type = type,
        createdAt = sequence,
        data = buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(message)))
        },
    )

    private fun message(
        id: String,
        content: String,
        createdAt: Long,
    ) = LocalHarnessMessage(
        id = id,
        role = "user",
        content = content,
        createdAt = createdAt,
    )
}

package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.RelationshipDynamics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatHistoryWindowTest {

    @Test
    fun continuationHandoffKeepsFactsAndUserEventsWithoutOldRoleWording() {
        val handoff = buildChatContinuationHandoff(
            state = ChatCharacterState(
                dynamics = RelationshipDynamics(
                    sharedMoments = listOf("一起看过日落"),
                ),
            ),
            messages = listOf(
                LocalHarnessMessage("u1", "user", "明天去海边", createdAt = 1L),
                LocalHarnessMessage("a1", "assistant", "我会一直陪着你去", createdAt = 2L),
            ),
        )

        assertTrue(handoff.contains("一起看过日落"))
        assertTrue(handoff.contains("明天去海边"))
        assertFalse(handoff.contains("我会一直陪着你去"))
        assertTrue(handoff.contains("角色旧回复原文省略"))
    }


    @Test
    fun preservesExistingCheckpointAndSummarizesTheGapAfterIt() {
        val existing = message(
            "user",
            "<compacted-summary>\n更早事件：第一次见面\n</compacted-summary>",
        )
        val history = buildList {
            add(message("system", "系统"))
            add(existing)
            repeat(16) { index ->
                add(message("user", "检查点后的用户事件$index"))
                add(message("assistant", "检查点后的角色回复$index"))
            }
        }

        val bounded = boundedChatRequestHistory(history, recentMessages = 10)
        val text = bounded.joinToString("\n") { it.toString() }

        assertTrue(text.contains("第一次见面"))
        assertFalse(text.contains("检查点后的用户事件0"))
        assertTrue(text.contains("检查点后的用户事件3"))
        assertFalse(text.contains("检查点后的角色回复0"))
        assertTrue(text.contains("检查点后的角色回复15"))
    }

    @Test
    fun keepsRecentDialogueAndSynthesizesUserOnlyContinuity() {
        val history = buildList {
            add(message("system", "系统"))
            repeat(15) { index ->
                add(message("user", "用户事件$index"))
                add(message("assistant", "角色旧回复$index"))
            }
        }

        val bounded = boundedChatRequestHistory(history, recentMessages = 10)
        val text = bounded.joinToString("\n") { it.toString() }

        assertFalse(text.contains("用户事件0"))
        assertTrue(text.contains("用户事件2"))
        assertFalse(text.contains("角色旧回复0"))
        assertTrue(text.contains("用户事件14"))
        assertTrue(text.contains("角色旧回复14"))
        assertTrue(text.contains("<chat-continuity>"))
        assertTrue(bounded.size <= 12)
    }

    @Test
    fun multimodalOlderUserMessageDoesNotBreakContinuityProjection() {
        val multimodal = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "图片问题") })
            })
        }
        val history = listOf(
            message("system", "系统"),
            multimodal,
            message("assistant", "旧回复"),
        ) + (0 until 12).flatMap { index ->
            listOf(message("user", "新问题$index"), message("assistant", "新回答$index"))
        }

        val bounded = boundedChatRequestHistory(history, recentMessages = 10)
        assertTrue(bounded.any { it.toString().contains("新问题11") })
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

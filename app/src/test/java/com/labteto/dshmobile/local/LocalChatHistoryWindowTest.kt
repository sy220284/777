package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.estimateModelTokens
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatHistoryWindowTest {

    @Test
    fun keepsHotWindowIntactUntilWholeBatchNeedsCompaction() {
        val history28 = buildList {
            add(message("system", "系统"))
            repeat(14) { index ->
                add(message("user", "用户事件$index"))
                add(message("assistant", "角色回复$index"))
            }
        }

        val bounded28 = boundedChatRequestHistory(
            history28,
            recentMessages = 20,
            compactionBatch = 8,
        )
        assertEquals(history28, bounded28)

        val history29 = history28 + message("user", "第29条消息")
        val bounded29 = boundedChatRequestHistory(
            history29,
            recentMessages = 20,
            compactionBatch = 8,
        )
        assertTrue(bounded29.any { it.toString().contains("<chat-continuity>") })
        assertEquals(23, bounded29.size)
        assertFalse(bounded29.joinToString("\n").contains("角色回复0"))
        assertTrue(bounded29.joinToString("\n").contains("用户事件0"))

        val history30 = history29 + message("assistant", "第30条消息")
        val bounded30 = boundedChatRequestHistory(
            history30,
            recentMessages = 20,
            compactionBatch = 8,
        )
        assertEquals(bounded29[1], bounded30[1])
    }

    @Test
    fun rebuildsRequestCheckpointOnlyAtNextBatchBoundary() {
        val base = buildList {
            add(message("system", "系统"))
            repeat(18) { index ->
                add(message("user", "用户事件$index"))
                add(message("assistant", "角色回复$index"))
            }
        }
        val bounded36 = boundedChatRequestHistory(base, recentMessages = 20, compactionBatch = 8)
        val bounded37 = boundedChatRequestHistory(
            base + message("user", "第37条消息"),
            recentMessages = 20,
            compactionBatch = 8,
        )

        assertTrue(bounded36.any { it.toString().contains("<chat-continuity>") })
        assertTrue(bounded37.any { it.toString().contains("<chat-continuity>") })
        assertFalse(bounded36[1] == bounded37[1])
        assertTrue(bounded37[1].toString().contains("用户事件7"))
    }

    @Test
    fun preservesExistingCheckpointAndSummarizesOnlyWholeBatchesAfterIt() {
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

        val bounded = boundedChatRequestHistory(
            history,
            recentMessages = 10,
            compactionBatch = 4,
        )
        val text = bounded.joinToString("\n") { it.toString() }

        assertTrue(text.contains("第一次见面"))
        assertFalse(text.contains("检查点后的用户事件0"))
        assertTrue(text.contains("检查点后的用户事件2"))
        assertFalse(text.contains("检查点后的角色回复0"))
        assertTrue(text.contains("检查点后的角色回复15"))
    }

    @Test
    fun requestCheckpointKeepsUserEventsButDropsOldRoleWording() {
        val history = buildList {
            add(message("system", "系统"))
            repeat(15) { index ->
                add(message("user", "用户事件$index"))
                add(message("assistant", "角色旧回复$index"))
            }
        }

        val bounded = boundedChatRequestHistory(
            history,
            recentMessages = 10,
            compactionBatch = 4,
        )
        val text = bounded.joinToString("\n") { it.toString() }

        assertTrue(text.contains("用户事件0"))
        assertFalse(text.contains("角色旧回复0"))
        assertTrue(text.contains("用户事件14"))
        assertTrue(text.contains("角色旧回复14"))
        assertTrue(text.contains("<chat-continuity>"))
        assertTrue(bounded.size <= 16)
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


    @Test
    fun existingCheckpointIsDemotedAndPrunedEvenBeforeNextCompactionBoundary() {
        val legacy = message(
            "user",
            "<chat-continuity>\n- 明早九点去城南\n- 第一次见面在车站\n</chat-continuity>",
        )
        val history = listOf(
            message("system", "系统"),
            legacy,
            message("user", "那改成十点吧"),
            message("assistant", "好，十点。"),
        )

        val bounded = boundedChatRequestHistory(
            history = history,
            recentMessages = 20,
            compactionBatch = 8,
            currentFacts = listOf("明早十点去城南"),
        )
        val checkpoint = bounded[1]

        assertEquals("system", checkpoint["role"]?.jsonPrimitive?.content)
        assertFalse(checkpoint.toString().contains("明早九点去城南"))
        assertTrue(checkpoint.toString().contains("第一次见面在车站"))
        assertTrue(bounded.last().toString().contains("好，十点"))
    }

    @Test
    fun generatedCheckpointIsSystemHistoryAndDropsCanonicalDuplicates() {
        val history = buildList {
            add(message("system", "系统"))
            repeat(15) { index ->
                add(
                    message(
                        "user",
                        if (index == 0) "明早九点去城南" else "旧事件$index",
                    ),
                )
                add(message("assistant", "角色旧回复$index"))
            }
        }

        val bounded = boundedChatRequestHistory(
            history = history,
            recentMessages = 10,
            compactionBatch = 4,
            currentFacts = listOf("明早九点去城南"),
        )
        val checkpoint = bounded.first { it.toString().contains("<chat-continuity>") }

        assertEquals("system", checkpoint["role"]?.jsonPrimitive?.content)
        assertFalse(checkpoint.toString().contains("明早九点去城南"))
    }

    @Test
    fun legacyUserCheckpointIsDemotedToSystemAtRequestTime() {
        val legacy = message(
            "user",
            "<compacted-summary>\n- 旧决定\n</compacted-summary>",
        )
        val history = buildList {
            add(message("system", "系统"))
            add(legacy)
            repeat(8) { index ->
                add(message("user", "新问题$index"))
                add(message("assistant", "新回答$index"))
            }
        }

        val bounded = boundedChatRequestHistory(
            history = history,
            recentMessages = 6,
            compactionBatch = 4,
        )
        val checkpoint = bounded.first { it.toString().contains("<compacted-summary>") }

        assertEquals("system", checkpoint["role"]?.jsonPrimitive?.content)
    }


    @Test
    fun oldScheduleWithDifferentClockIsRemovedByCurrentDecision() {
        val history = buildList {
            add(message("system", "系统"))
            repeat(15) { index ->
                add(message("user", if (index == 0) "明早九点去城南" else "旧事件$index"))
                add(message("assistant", "角色旧回复$index"))
            }
        }

        val bounded = boundedChatRequestHistory(
            history = history,
            recentMessages = 10,
            compactionBatch = 4,
            currentFacts = listOf("明早十点去城南"),
        )
        val checkpoint = bounded.first { it.toString().contains("<chat-continuity>") }

        assertFalse(checkpoint.toString().contains("明早九点去城南"))
    }

    @Test
    fun groupWindowKeepsOlderPublicSpeakerEventsAndRecentDialogue() {
        val history = buildList {
            add(message("system", "群聊系统"))
            repeat(30) { index ->
                add(message("user", "用户事件$index"))
                add(message("assistant", "[角色A] 公开回应$index"))
            }
        }

        val bounded = boundedGroupChatRequestHistory(
            history = history,
            recentMessages = 12,
            compactionBatch = 4,
            maxContinuityEvents = 8,
        )
        val text = bounded.joinToString("\n") { it.toString() }

        assertTrue(text.contains("<group-chat-continuity>"))
        assertTrue(text.contains("群聊成员："))
        assertTrue(text.contains("公开回应"))
        assertTrue(text.contains("用户事件29"))
        assertTrue(text.contains("公开回应29"))
        assertTrue(bounded.size <= 22)
    }

    @Test
    fun groupRequestHistoryStaysBoundedAsDurableTranscriptGrows() {
        fun history(turns: Int) = buildList {
            add(message("system", "群聊系统"))
            repeat(turns) { index ->
                add(message("user", "用户第${index}轮：" + "问".repeat(40)))
                add(message("assistant", "[角色A] " + "答".repeat(160)))
                add(message("assistant", "[角色B] " + "应".repeat(160)))
            }
        }

        val oneHundred = boundedGroupChatRequestHistory(history(100))
        val fiveHundred = boundedGroupChatRequestHistory(history(500))
        val tokens100 = oneHundred.sumOf { estimateModelTokens(it.toString()) }
        val tokens500 = fiveHundred.sumOf { estimateModelTokens(it.toString()) }

        assertTrue(oneHundred.size <= 46)
        assertTrue(fiveHundred.size <= 46)
        assertTrue(tokens500 <= tokens100 + 1_000)
        assertTrue(fiveHundred.joinToString("\n").contains("用户第499轮"))
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

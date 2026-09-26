package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalSessionTranscriptProjectionTest {
    @Test
    fun regeneratedAnswerReplacesOldBubbleAndOldModelReplyOnRestart() {
        val old = message("old", "assistant", "原回答", 1L)
        val replacement = message("new", "assistant", "新回答", 2L)
        val event = LocalSessionEventLog.Event(
            sequence = 4L,
            type = "assistant/message",
            createdAt = 2L,
            data = buildJsonObject {
                put("role", "assistant")
                put("content", "新回答")
                put("replaces", "old")
                put("transcript", encodeTranscriptMessages(listOf(replacement)))
            },
        )
        val projected = projectSessionTranscriptTail(listOf(old), listOf(event), 3L)
        assertEquals(listOf(replacement), projected.messages)
        val history = restoreLocalModelHistory(
            events = listOf(
                LocalSessionEventLog.Event(1L, "user/message", 1L, buildJsonObject { put("content", "问题") }),
                LocalSessionEventLog.Event(2L, "assistant/message", 1L, buildJsonObject {
                    put("role", "assistant"); put("content", "原回答")
                }),
                event,
            ),
            legacyFallback = emptyList(),
            codec = ModelHistoryCheckpointCodec(),
        )
        assertEquals(listOf("问题", "新回答"), history.messages.map { it["content"].toString().trim('"') })
        assertFalse(history.messages.last().containsKey("replaces"))
    }

    @Test
    fun replaysTranscriptTailInSequenceOrderAndDeduplicatesByStableId() {
        val existing = message("m-old", "user", "旧消息", 10L)
        val duplicate = existing.copy(content = "不应覆盖")
        val reasoning = message("m-reason", "reasoning", "分析", 20L)
        val answer = message("m-answer", "assistant", "完成", 30L)

        val events = listOf(
            event(4L, encodeTranscriptMessages(listOf(message("too-old", "system", "过期", 1L)))),
            event(6L, encodeTranscriptMessages(listOf(duplicate, reasoning))),
            LocalSessionEventLog.Event(
                sequence = 7L,
                type = "step/end",
                createdAt = 1L,
                data = buildJsonObject { put("step", 1) },
            ),
            LocalSessionEventLog.Event(
                sequence = 8L,
                type = "assistant/message",
                createdAt = 1L,
                data = buildJsonObject {
                    put("transcript", JsonArray(listOf(buildJsonObject {
                        put("id", "broken")
                        put("role", "assistant")
                    })))
                },
            ),
            event(9L, encodeTranscriptMessages(listOf(answer))),
        )

        val projected = projectSessionTranscriptTail(
            snapshotMessages = listOf(existing),
            events = events,
            sequenceExclusive = 5L,
        )

        assertEquals(listOf("m-old", "m-reason", "m-answer"), projected.messages.map { it.id })
        assertEquals("旧消息", projected.messages.first().content)
        assertEquals(9L, projected.projectedThroughSequence)
    }

    @Test
    fun boundedProjectionKeepsOnlyNewestRuntimeWindow() {
        val snapshot = (0 until 4).map { index ->
            message("s$index", if (index % 2 == 0) "user" else "assistant", "旧$index", index.toLong())
        }
        val tail = listOf(
            event(10L, encodeTranscriptMessages(listOf(message("n1", "user", "新1", 10L)))),
            event(11L, encodeTranscriptMessages(listOf(message("n2", "assistant", "新2", 11L)))),
        )

        val projected = projectSessionTranscriptTail(
            snapshotMessages = snapshot,
            events = tail,
            sequenceExclusive = 9L,
            maxMessages = 3,
        )

        assertEquals(listOf("s3", "n1", "n2"), projected.messages.map { it.id })
        assertEquals(11L, projected.projectedThroughSequence)
    }

    @Test
    fun activeTranscriptEventRestoresSelectedConversationBranch() {
        val oldUser = message("u-old", "user", "原问题", 1L)
        val oldAnswer = message("a-old", "assistant", "原回答", 2L)
        val editedUser = message("u-new", "user", "修改后的问题", 3L)
        val editedAnswer = message("a-new", "assistant", "新回答", 4L)
        val branchEvent = LocalSessionEventLog.Event(
            sequence = 11L,
            type = "chat/active-transcript",
            createdAt = 5L,
            data = buildJsonObject {
                put("reason", "variant-selected")
                put("transcript", encodeTranscriptMessages(listOf(editedUser, editedAnswer)))
            },
        )

        val projected = projectSessionTranscriptTail(
            snapshotMessages = listOf(oldUser, oldAnswer),
            events = listOf(branchEvent),
            sequenceExclusive = 10L,
        )

        assertEquals(listOf("u-new", "a-new"), projected.messages.map { it.id })
        assertEquals(11L, projected.projectedThroughSequence)
    }

    @Test
    fun legacyTranscriptUsesDurableBaselineAndNewSessionStartsAtBeginning() {
        val legacy = LocalHarnessSession(id = "legacy", transcriptProjectedThroughSequence = null)

        assertEquals(
            44L,
            transcriptProjectionReplayCursor(
                snapshot = legacy,
                persistedSnapshotExists = true,
                legacyBaselineSequence = 44L,
            ),
        )
        assertEquals(
            -1L,
            transcriptProjectionReplayCursor(
                snapshot = legacy,
                persistedSnapshotExists = false,
                legacyBaselineSequence = null,
            ),
        )
    }

    @Test
    fun assistantTranscriptMetadataNeverLeaksBackIntoModelHistory() {
        val raw = buildJsonObject {
            put("role", "assistant")
            put("content", "模型回答")
            put("transcript", encodeTranscriptMessages(listOf(
                message("m1", "assistant", "界面回答", 1L),
            )))
        }

        val modelMessage = assistantModelMessageFromEvent(raw)

        assertEquals("assistant", (modelMessage["role"] as JsonPrimitive).content)
        assertEquals("模型回答", (modelMessage["content"] as JsonPrimitive).content)
        assertFalse(modelMessage.containsKey("transcript"))
    }

    @Test
    fun transcriptMetadataIsStrippedDuringActualModelHistoryReplay() {
        val raw = buildJsonObject {
            put("role", "assistant")
            put("content", "模型回答")
            put("transcript", encodeTranscriptMessages(listOf(
                message("m-history", "assistant", "界面回答", 2L),
            )))
        }
        val restored = restoreLocalModelHistory(
            events = listOf(
                LocalSessionEventLog.Event(
                    sequence = 0L,
                    type = "assistant/message",
                    createdAt = 2L,
                    data = raw,
                ),
            ),
            legacyFallback = emptyList(),
            codec = ModelHistoryCheckpointCodec(),
        )

        assertEquals(1, restored.messages.size)
        assertFalse(restored.messages.single().containsKey("transcript"))
        assertEquals(
            "模型回答",
            (restored.messages.single()["content"] as JsonPrimitive).content,
        )
    }

    @Test
    fun transcriptRoundTripPreservesGroupSpeakerMetadata() {
        val reply = LocalHarnessMessage(
            id = "group-a1",
            role = "assistant",
            content = "我在。",
            createdAt = 88L,
            speakerId = "gallery-ayaka",
            speakerName = "神里绫华",
        )
        val projected = projectSessionTranscriptTail(
            snapshotMessages = emptyList(),
            events = listOf(event(0L, encodeTranscriptMessages(listOf(reply)))),
            sequenceExclusive = -1L,
        )

        assertEquals(listOf(reply), projected.messages)
        assertEquals("gallery-ayaka", projected.messages.single().speakerId)
        assertEquals("神里绫华", projected.messages.single().speakerName)
    }

    @Test
    fun transcriptRoundTripPreservesProactiveRoleMetadata() {
        val proactive = LocalHarnessMessage(
            id = "auto-a1",
            role = "assistant",
            content = "突然想起来一件事。",
            createdAt = 90L,
            proactive = true,
        )
        val projected = projectSessionTranscriptTail(
            snapshotMessages = emptyList(),
            events = listOf(event(0L, encodeTranscriptMessages(listOf(proactive)))),
            sequenceExclusive = -1L,
        )

        assertEquals(listOf(proactive), projected.messages)
        assertEquals(true, projected.messages.single().proactive)
    }

    @Test
    fun transcriptRoundTripPreservesToolMetadata() {
        val tool = message("tool-1", "tool", "输出", 99L, toolName = "read")
        val projected = projectSessionTranscriptTail(
            snapshotMessages = emptyList(),
            events = listOf(event(0L, encodeTranscriptMessages(listOf(tool)))),
            sequenceExclusive = -1L,
        )

        assertEquals(listOf(tool), projected.messages)
        assertEquals(0L, projected.projectedThroughSequence)
    }

    private fun event(sequence: Long, transcript: JsonArray) = LocalSessionEventLog.Event(
        sequence = sequence,
        type = "transcript-bearing",
        createdAt = 1L,
        data = buildJsonObject { put("transcript", transcript) },
    )

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        toolName: String? = null,
    ) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        toolName = toolName,
        createdAt = createdAt,
    )
}

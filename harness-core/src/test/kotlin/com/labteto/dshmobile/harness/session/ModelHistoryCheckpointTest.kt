package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelHistoryCheckpointTest {
    private val codec = ModelHistoryCheckpointCodec()

    @Test
    fun roundTripsValidHistory() {
        val history = listOf(
            buildJsonObject { put("role", "system"); put("content", "规则") },
            buildJsonObject { put("role", "user"); put("content", "你好") },
        )

        val encoded = codec.encode(
            messages = history,
            reason = "user/message",
            asOfSequence = 42L,
        )
        val restored = requireNotNull(codec.decodeCheckpoint(encoded))

        assertEquals(history, restored.messages)
        assertEquals("user/message", restored.reason)
        assertEquals(42L, restored.asOfSequence)
        assertEquals(ModelHistoryCheckpointCodec.CURRENT_VERSION, restored.version)
    }

    @Test
    fun rejectsFutureVersion() {
        val encoded = buildJsonObject {
            put("version", 3)
            put("messages", JsonArray(emptyList()))
        }
        assertNull(codec.decode(encoded))
    }

    @Test
    fun restoresVersionOneCheckpointWithoutWatermark() {
        val encoded = buildJsonObject {
            put("version", 1)
            put("reason", "legacy")
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "旧检查点")
                        },
                    ),
                ),
            )
        }

        val restored = requireNotNull(codec.decodeCheckpoint(encoded))

        assertEquals(1, restored.version)
        assertEquals("legacy", restored.reason)
        assertEquals(null, restored.asOfSequence)
        assertEquals("旧检查点", restored.messages.single()["content"]?.toString()?.trim('"'))
    }

    @Test
    fun rejectsNonObjectOrRolelessMessages() {
        val scalar = buildJsonObject {
            put("version", 1)
            put("messages", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("bad"))))
        }
        assertNull(codec.decode(scalar))

        val roleless = buildJsonObject {
            put("version", 1)
            put("messages", JsonArray(listOf(buildJsonObject { put("content", "bad") })))
        }
        assertNull(codec.decode(roleless))
    }
}

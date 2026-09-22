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

        val encoded = codec.encode(history, "user/message")
        val restored = requireNotNull(codec.decode(encoded))

        assertEquals(history, restored)
        assertEquals("user/message", encoded["reason"]?.toString()?.trim('"'))
    }

    @Test
    fun rejectsFutureVersion() {
        val encoded = buildJsonObject {
            put("version", 2)
            put("messages", JsonArray(emptyList()))
        }
        assertNull(codec.decode(encoded))
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

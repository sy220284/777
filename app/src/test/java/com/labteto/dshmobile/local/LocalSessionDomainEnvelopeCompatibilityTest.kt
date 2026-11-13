package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.LocalChatSessionDomainCodec
import com.labteto.dshmobile.local.chat.chatState
import com.labteto.dshmobile.local.chat.replySuggestions
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.normalizeLocalSessionDomains
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.local.work.LocalWorkSessionDomainCodec
import com.labteto.dshmobile.local.work.goal
import com.labteto.dshmobile.local.work.todos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionDomainEnvelopeCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun legacyDomainFieldNamesDecodeThroughFeatureOwnedCodecsAndRemainStableOnWrite() {
        val chat = ChatCharacterState(mood = "稳定")
        val todos = listOf(LocalTodoItem("收口边界", "in_progress"))
        val goal = LocalGoal("完成架构3.0")
        val legacy = buildJsonObject {
            put("id", "legacy-domain")
            put("chatState", json.encodeToJsonElement(chat))
            put("chatContext", JsonObject(emptyMap()))
            put("replySuggestions", JsonArray(emptyList()))
            put("chatBranches", JsonObject(emptyMap()))
            put("groupChat", JsonObject(emptyMap()))
            put("todos", json.encodeToJsonElement(todos))
            put("goal", json.encodeToJsonElement(goal))
        }

        val decoded = json.decodeFromJsonElement(LocalHarnessSession.serializer(), legacy)
        val normalized = normalizeLocalSessionDomains(
            decoded,
            listOf(LocalChatSessionDomainCodec, LocalWorkSessionDomainCodec),
        )

        assertEquals("稳定", normalized.chatState.mood)
        assertTrue(normalized.replySuggestions.isEmpty())
        assertEquals(todos, normalized.todos)
        assertEquals(goal, normalized.goal)

        val persisted = json.encodeToJsonElement(LocalHarnessSession.serializer(), normalized).jsonObject
        assertTrue("chatState" in persisted)
        assertTrue("chatContext" in persisted)
        assertTrue("replySuggestions" in persisted)
        assertTrue("chatBranches" in persisted)
        assertTrue("groupChat" in persisted)
        assertTrue("todos" in persisted)
        assertTrue("goal" in persisted)
        assertFalse("chatStatePayload" in persisted)
        assertFalse("todosPayload" in persisted)
        assertFalse("goalPayload" in persisted)
    }
}

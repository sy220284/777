package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class LocalHarnessSessionApprovalTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacyApprovalFieldIsAcceptedButNoLongerPersistedPerSession() {
        val session = json.decodeFromString(
            LocalHarnessSession.serializer(),
            """{"id":"legacy","title":"旧会话","planMode":false,"autoApproveMutations":true}""",
        )
        val encoded = json.encodeToString(LocalHarnessSession.serializer(), session)

        assertFalse(encoded.contains("autoApproveMutations"))
    }

    @Test
    fun legacyApprovalValueCanSeedGlobalMigration() {
        val enabled = json.parseToJsonElement(
            """{"id":"legacy","autoApproveMutations":true}""",
        ).jsonObject
        val disabled = json.parseToJsonElement(
            """{"id":"legacy"}""",
        ).jsonObject

        assertTrue(legacySafeAutoApproval(enabled))
        assertFalse(legacySafeAutoApproval(disabled))
    }

    @Test
    fun legacySessionDefaultsToIndependentConversation() {
        val session = json.decodeFromString(
            LocalHarnessSession.serializer(),
            """{"id":"legacy-context","title":"旧会话"}""",
        )

        assertEquals(LocalConversationMode.INDEPENDENT, session.conversationMode)
        assertEquals(null, session.parentSessionId)
        assertEquals("", session.lineageId)
    }

    @Test
    fun conversationMetadataRoundTrips() {
        val encoded = json.encodeToString(
            LocalHarnessSession.serializer(),
            LocalHarnessSession(
                id = "child",
                conversationMode = LocalConversationMode.CONTINUATION,
                parentSessionId = "parent",
                lineageId = "lineage",
                projectId = "project",
                handoffSummary = "交接摘要",
            ),
        )
        val restored = json.decodeFromString(LocalHarnessSession.serializer(), encoded)

        assertEquals(LocalConversationMode.CONTINUATION, restored.conversationMode)
        assertEquals("parent", restored.parentSessionId)
        assertEquals("lineage", restored.lineageId)
        assertEquals("project", restored.projectId)
        assertEquals("交接摘要", restored.handoffSummary)
    }
}

package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalHarnessSessionApprovalTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacySessionDefaultsToPerOperationApproval() {
        val session = json.decodeFromString(
            LocalHarnessSession.serializer(),
            """{"id":"legacy","title":"旧会话","planMode":false}""",
        )

        assertFalse(session.autoApproveMutations)
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

    @Test
    fun autoApprovalRoundTripsWithSession() {
        val encoded = json.encodeToString(
            LocalHarnessSession.serializer(),
            LocalHarnessSession(id = "auto", autoApproveMutations = true),
        )
        val restored = json.decodeFromString(LocalHarnessSession.serializer(), encoded)

        assertTrue(restored.autoApproveMutations)
    }
}

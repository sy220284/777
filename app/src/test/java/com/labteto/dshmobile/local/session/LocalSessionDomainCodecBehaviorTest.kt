package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionDomainCodecBehaviorTest {
    @Serializable
    private data class Payload(val value: String)

    @Test
    fun domainJsonRoundTripsTypedPayload() {
        val encoded = encodeLocalSessionDomain(Payload("ok"))
        val decoded = decodeLocalSessionDomain<Payload>(encoded)

        assertEquals(Payload("ok"), decoded)
    }

    @Test
    fun normalizationRunsInRegistrationOrder() {
        val first = codec(
            id = "first",
            normalize = { it.copy(title = it.title + "-first") },
        )
        val second = codec(
            id = "second",
            normalize = { it.copy(title = it.title + "-second") },
        )

        val normalized = normalizeLocalSessionDomains(
            LocalHarnessSession(id = "s", title = "base"),
            listOf(first, second),
        )

        assertEquals("base-first-second", normalized.title)
    }

    @Test
    fun summaryUsesNormalizedSessionAndProjectsThroughEveryCodec() {
        val longPreview = "  hello   world  " + "x".repeat(80)
        val normalize = codec(
            id = "normalize",
            normalize = { it.copy(title = "normalized") },
            project = { _, summary -> summary.copy(chatMode = "GROUP") },
        )
        val project = codec(
            id = "project",
            project = { _, summary -> summary.copy(groupMemberCount = 3, personaId = "p") },
        )
        val session = LocalHarnessSession(
            id = "session-1",
            title = "old",
            updatedAt = 42L,
            usageMode = LocalUsageMode.CHAT,
            projectId = "project-1",
            lineageId = "",
            transcriptIndex = LocalTranscriptRuntimeIndex(
                totalMessageCount = 1L,
                latestUserContent = longPreview,
            ),
        )

        val summary = projectLocalSessionSummary(session, listOf(normalize, project))

        assertEquals("session-1", summary.id)
        assertEquals("normalized", summary.title)
        assertEquals(42L, summary.updatedAt)
        assertEquals(LocalUsageMode.CHAT, summary.usageMode)
        assertFalse(summary.blank)
        assertEquals("project-1", summary.projectId)
        assertEquals("session-1", summary.lineageId)
        assertEquals("GROUP", summary.chatMode)
        assertEquals(3, summary.groupMemberCount)
        assertEquals("p", summary.personaId)
        assertEquals(73, requireNotNull(summary.summaryPreview).length)
        assertTrue(summary.summaryPreview!!.endsWith("…"))
        assertFalse(summary.summaryPreview!!.contains("  "))
    }

    @Test
    fun blankSummaryRequiresNoIndexedOrVisibleNonBlankContent() {
        val blank = projectLocalSessionSummary(
            LocalHarnessSession(
                id = "blank",
                transcriptWindow = listOf(message("w", "   ")),
                messages = listOf(message("m", "\n")),
            ),
            emptyList(),
        )
        assertTrue(blank.blank)
        assertNull(blank.summaryPreview)

        val indexed = projectLocalSessionSummary(
            LocalHarnessSession(
                id = "indexed",
                transcriptIndex = LocalTranscriptRuntimeIndex(totalMessageCount = 1L),
            ),
            emptyList(),
        )
        assertFalse(indexed.blank)

        val window = projectLocalSessionSummary(
            LocalHarnessSession(
                id = "window",
                transcriptWindow = listOf(message("visible", "content")),
            ),
            emptyList(),
        )
        assertFalse(window.blank)

        val legacy = projectLocalSessionSummary(
            LocalHarnessSession(
                id = "legacy",
                messages = listOf(message("legacy", "content")),
            ),
            emptyList(),
        )
        assertFalse(legacy.blank)
    }

    @Test
    fun newEditorIsNotDurableUntilItHasActualConversationOrWorkFacts() {
        val newChat = LocalHarnessSession(id = "pending-chat", usageMode = LocalUsageMode.CHAT)
        assertTrue(isUnstartedSessionSnapshot(newChat, emptyList()))
        assertFalse(isUnstartedSessionSnapshot(
            newChat.copy(transcriptIndex = LocalTranscriptRuntimeIndex(totalMessageCount = 1L)),
            emptyList(),
        ))
        assertFalse(isUnstartedSessionSnapshot(
            newChat.copy(transcriptWindow = listOf(message("u", "hello"))), emptyList(),
        ))
        assertFalse(isUnstartedSessionSnapshot(
            newChat.copy(handoffSummary = "移交的内容"), emptyList(),
        ))
        assertFalse(isUnstartedSessionSnapshot(
            LocalHarnessSession(id = "work", plan = listOf("真实任务")),
            emptyList(),
        ))
        val featureCodec = object : LocalSessionDomainCodec {
            override val id = "feature"
            override fun normalizeLoaded(session: LocalHarnessSession) = session
            override fun hasSessionWork(session: LocalHarnessSession) = session.personaId == "custom"
        }
        assertFalse(isUnstartedSessionSnapshot(
            newChat.copy(personaId = "custom"), listOf(featureCodec),
        ))
    }

    private fun message(id: String, content: String) = LocalHarnessMessage(
        id = id,
        role = "user",
        content = content,
        createdAt = 1L,
    )

    private fun codec(
        id: String,
        normalize: (LocalHarnessSession) -> LocalHarnessSession = { it },
        project: (LocalHarnessSession, LocalSessionSummary) -> LocalSessionSummary = { _, summary -> summary },
    ): LocalSessionDomainCodec = object : LocalSessionDomainCodec {
        override val id: String = id

        override fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession = normalize(session)

        override fun projectSummary(
            session: LocalHarnessSession,
            summary: LocalSessionSummary,
        ): LocalSessionSummary = project(session, summary)
    }
}

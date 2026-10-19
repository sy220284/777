package com.labteto.dshmobile.local.presentation.document

import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentSource
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalReplyUiDocumentTest {
    private val raw = """{"schemaVersion":1,"documentId":"model-id","revision":0,"title":"Choose",
        "nodes":[{"id":"choice","type":"select","label":"Choice","options":["A","B"],"initial":"A"},
        {"id":"go","type":"button","label":"Continue","actionId":"send"}],
        "actions":[{"id":"send","type":"submit_user_input","text":"Continue","fieldIds":["choice"]}],
        "fallbackText":"Choose A or B"}""".trimIndent()

    @Test fun protocolLeavesNormalCodeAndPlainAnswersIntactAndHidesIncompletePayload() {
        val plain = "Use a form\n```json\n{}\n```"
        assertEquals(plain, LocalReplyUiDocumentProtocol.split(plain).text)
        val answer = "Choose an option.\n```ui_document\n$raw\n```"
        assertEquals("Choose an option.", LocalReplyUiDocumentProtocol.split(answer).text)
        assertEquals(raw, LocalReplyUiDocumentProtocol.split(answer).definition)
        val cardOnly = LocalReplyUiDocumentProtocol.split("```ui_document\n$raw\n```")
        assertEquals("Choose A or B", cardOnly.text)
        assertEquals(raw, cardOnly.definition)
        val incomplete = LocalReplyUiDocumentProtocol.split("Still useful\n```ui_document\n{unfinished")
        assertEquals("Still useful", incomplete.text)
        assertNull(incomplete.definition)
        val windows = answer.replace("```ui_document\n", "```ui_document\r\n")
        assertEquals("Choose an option.", LocalReplyUiDocumentProtocol.split(windows).text)
        assertEquals(raw, LocalReplyUiDocumentProtocol.split(windows).definition)
    }

    @Test fun workNeverShowsAnUnansweredFormWhileThatModelStepStillRunsTools() {
        assertFalse(LocalReplyUiDocumentProtocol.workFormMayBePublished(raw, modelToolCallCount = 1))
        assertFalse(LocalReplyUiDocumentProtocol.workFormMayBePublished(raw, modelToolCallCount = 2))
        assertTrue(LocalReplyUiDocumentProtocol.workFormMayBePublished(raw, modelToolCallCount = 0))
        assertFalse(LocalReplyUiDocumentProtocol.workFormMayBePublished(null, modelToolCallCount = 0))
    }

    @Test fun protocolDistinguishesConfirmAndSendFromPreparingUnsentDraft() {
        assertTrue(LocalReplyUiDocumentProtocol.instruction.contains(
            "submit_user_input for confirmation or submission buttons",
        ))
        assertTrue(LocalReplyUiDocumentProtocol.instruction.contains(
            "Do not call send_user_input a confirmation or submission",
        ))
    }

    @Test fun streamingHidesProtocolEvenWhenMarkerIsSplitAtEveryPossibleBoundary() {
        val output = "Progress 😀\n```ui_document\n$raw\n```"
        for (width in 1..30) {
            val preview = LocalReplyUiDocumentPreview()
            val visible = output.chunked(width).joinToString("") { preview.append(it) }
            assertEquals("Progress 😀\n", visible)
            assertEquals("", preview.append("more hidden JSON"))
        }
        val windows = output.replace("```ui_document\n", "```ui_document\r\n")
        for (width in 1..30) {
            val preview = LocalReplyUiDocumentPreview()
            assertEquals("Progress 😀\n", windows.chunked(width).joinToString("") { preview.append(it) })
        }
        val plain = LocalReplyUiDocumentPreview()
        assertEquals("Normal code", plain.append("Normal code"))
        assertEquals("\n```json\n{}", plain.append("\n```json\n{}"))
    }

    @Test fun automaticCardRestoresExactOwnerSourceAndCannotReviveAfterDeletion() {
        val root = Files.createTempDirectory("reply-ui").toFile()
        val logs = LocalSessionEventLogRegistry(root, Json)
        try {
            val store = LocalUiDocumentSessionStore(logs)
            val publisher = LocalReplyUiDocumentPublisher(store)
            val source = UiDocumentSource("s", "assistant-1", 4, "p", "project")
            assertTrue(publisher.publish(raw, source, "actual-request", "actual-model", true))
            val budget = LocalReplyUiDocumentProtocol.budget
            val record = LocalUiDocumentSessionStore(logs).read("s", setOf("assistant-1"), budget, emptySet()).single()
            assertEquals(source, record.document.source)
            assertEquals("reply-ui:assistant-1", record.document.documentId)
            assertEquals("actual-request", record.provenance.requestId)
            assertTrue((record.document.actions.single() as UiDocumentAction.SendUserInput).submit)
            assertTrue(publisher.publish(raw.replace("Choose", "Updated"), source, "request-2", "m"))
            val latest = store.latestDocument("s", "assistant-1", record.document.documentId, budget, emptySet())!!.document
            assertEquals(1L, latest.revision)
            assertTrue(store.delete(latest, budget, emptySet()))
            assertFalse(publisher.publish(raw, source, "late", "m"))
            assertTrue(store.read("s", setOf("assistant-1"), budget, emptySet()).isEmpty())
            assertTrue(store.read("other", setOf("assistant-1"), budget, emptySet()).isEmpty())
        } finally { logs.clearAndEvict(setOf("s", "other")); root.deleteRecursively() }
    }

    @Test fun modelCannotAuthorizeToolsApprovalOrReplaceSource() {
        val root = Files.createTempDirectory("reply-ui-invalid").toFile()
        val logs = LocalSessionEventLogRegistry(root, Json)
        try {
            val store = LocalUiDocumentSessionStore(logs)
            val publisher = LocalReplyUiDocumentPublisher(store)
            val source = UiDocumentSource("s", "a", 0, null, null)
            assertFalse(publisher.publish(raw.replace("submit_user_input", "execute_tool"), source, "r", "m"))
            assertFalse(publisher.publish(raw.replace("\"revision\":0", "\"revision\":0,\"source\":{}"), source, "r", "m"))
            val globalBinding = raw.replace("\"nodes\":[", "\"nodes\":[{\"id\":\"global\",\"type\":\"bound_text\",\"binding\":\"EXECUTION_PHASE\",\"label\":\"phase\"},")
            assertFalse(publisher.publish(globalBinding, source, "r", "m"))
            assertFalse(publisher.publish("{broken", source, "r", "m"))
            assertTrue(store.read("s", setOf("a"), LocalReplyUiDocumentProtocol.budget, emptySet()).isEmpty())
        } finally { logs.clearAndEvict(setOf("s")); root.deleteRecursively() }
    }
}

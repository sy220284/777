package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.uicontract.*
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LocalUiDocumentSessionStoreTest {
    private val budget = UiDocumentBudget(64, 8, 4096)
    private val provenance = UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL)
    private fun document(message: String) = LocalUiDocumentBuilder.build("document-$message",
        UiDocumentSource("session", message, 2, "profile", null), 0,
        UiLocalDocumentData.ListItems(listOf("preserved")), budget, emptySet()).getOrThrow()

    @Test fun lateProducerCannotOverwriteNewRevisionAndLocalProvenanceRestores() {
        val directory = Files.createTempDirectory("ui-doc-revision").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val owner = LocalUiDocumentSessionStore(registry)
            val original = document("message")
            assertTrue(owner.publish(original, provenance, null, null, budget, emptySet()))
            val newer = original.copy(revision = 1, title = "newer")
            assertTrue(owner.publish(newer, provenance, null, original, budget, emptySet()))
            assertFalse(owner.publish(original.copy(revision = 1, title = "late"), provenance, null, original, budget, emptySet()))
            assertFalse(owner.publish(newer.copy(revision = 3), provenance, null, newer, budget, emptySet()))
            val restored = LocalUiDocumentSessionStore(registry).read("session", setOf("message"), budget, emptySet()).single()
            assertEquals(newer, restored.document)
            assertEquals(UiDocumentOrigin.LOCAL, restored.provenance.origin)
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun damagedSourceOnlyDisablesItsOwnCard() {
        val directory = Files.createTempDirectory("ui-doc-corrupt").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val owner = LocalUiDocumentSessionStore(registry)
            val good = document("good")
            assertTrue(owner.publish(good, provenance, null, null, budget, emptySet()))
            registry.get("session").append(UI_DOCUMENT_EVENT, buildJsonObject {
                put("source", buildJsonObject { put("sessionId", "session"); put("messageId", "bad"); put("contextGeneration", "damaged") })
                put("definition", buildJsonObject { put("fallbackText", "original text") })
            })
            val records = LocalUiDocumentSessionStore(registry).read("session", setOf("good", "bad"), budget, emptySet())
            assertEquals(2, records.size)
            assertEquals(good, records.single { it.document.source.messageId == "good" }.document)
            val bad = records.single { it.document.source.messageId == "bad" }
            assertNotNull(bad.recoveryNotice)
            assertTrue(bad.document.actions.isEmpty())
            assertEquals("original text", (bad.document.nodes.single() as UiDocumentNode.Text).text)
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }
}

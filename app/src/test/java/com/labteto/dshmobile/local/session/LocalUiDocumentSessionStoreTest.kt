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

    @Test fun userEditedDocumentIsCASVersionedAndCannotResurrectDeletedText() {
        val directory = Files.createTempDirectory("ui-doc-user-edit").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val first = document("message")
            assertTrue(store.publish(first, provenance, null, null, budget, emptySet()))
            val edited = first.withUserEditedText("item_0", "my corrected description")!!
            val userProvenance = UiDocumentProvenance(null, "user-edited", false, UiDocumentOrigin.LOCAL)
            assertTrue(store.publish(edited, userProvenance, null, first, budget, emptySet()))
            assertFalse(store.publish(first.withUserEditedText("item_0", "stale content")!!,
                userProvenance, null, first, budget, emptySet()))
            val restored = LocalUiDocumentSessionStore(registry).read(
                "session", setOf("message"), budget, emptySet()).single()
            assertEquals(edited, restored.document)
            assertEquals("user-edited", restored.provenance.model)
            assertTrue(store.delete(edited, budget, emptySet()))
            assertFalse(store.publish(edited.copy(revision = 2), userProvenance,
                null, edited, budget, emptySet()))
            assertNull(store.latestDocument("session", "message", first.documentId, budget, emptySet()))
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

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
    @Test fun multipleCardsOnOneMessageKeepIndependentRevisionsAcrossReopen() {
        val directory = Files.createTempDirectory("ui-doc-multi").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val designed = document("same")
            val progress = designed.copy(
                documentId = "work-live:same", title = "实时事件",
                nodes = listOf(UiDocumentNode.Text("event", "已持久化工具结果")),
                fallbackText = "执行事件", actions = emptyList(),
            )
            assertTrue(store.publish(designed, provenance, null, null, budget, emptySet()))
            assertTrue(store.publish(progress, provenance, null, null, budget, emptySet()))
            val progressNew = progress.copy(revision = 1, title = "实时事件更新")
            assertTrue(store.publish(progressNew, provenance, null, progress, budget, emptySet()))
            assertFalse(store.publish(progress.copy(revision = 1), provenance, null, progress, budget, emptySet()))
            val restored = LocalUiDocumentSessionStore(registry).read(
                "session", setOf("same"), budget, emptySet())
            assertEquals(2, restored.size)
            assertEquals(designed, restored.single { it.document.documentId == designed.documentId }.document)
            assertEquals(progressNew, restored.single { it.document.documentId == progressNew.documentId }.document)
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun unrelatedCardCannotClaimAnotherCardRevision() {
        val directory = Files.createTempDirectory("ui-doc-source").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val first = document("same")
            assertTrue(store.publish(first, provenance, null, null, budget, emptySet()))
            assertFalse(store.publish(first.copy(
                source = first.source.copy(contextGeneration = 999),
                revision = 1), provenance, null, first, budget, emptySet()))
            assertEquals(first, store.read("session", setOf("same"), budget, emptySet()).single().document)
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun deletingOneCardLeavesOtherCardsAndCannotBeResurrectedByLateWorkEvents() {
        val directory = Files.createTempDirectory("ui-doc-tombstone").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val designed = document("same")
            val progress = designed.copy(documentId = "work-live:same", actions = emptyList())
            assertTrue(store.publish(designed, provenance, null, null, budget, emptySet()))
            assertTrue(store.publish(progress, provenance, null, null, budget, emptySet()))
            assertEquals(2, store.read("session", setOf("same"), budget, emptySet()).size)
            assertTrue(store.delete(progress, budget, emptySet()))
            assertFalse(store.delete(progress, budget, emptySet()))
            assertFalse(store.publish(progress, provenance, null, null, budget, emptySet()))
            assertFalse(store.publish(progress.copy(revision = 1), provenance, null,
                progress, budget, emptySet()))
            assertEquals(designed, store.read("session", setOf("same"), budget,
                emptySet()).single().document)
            assertNull(store.latestDocument("session", "same", progress.documentId,
                budget, emptySet()))
            assertEquals(designed, LocalUiDocumentSessionStore(registry)
                .read("session", setOf("same"), budget, emptySet()).single().document)
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun incrementalReplayReadsNewCardsWithoutDroppingExistingVisibleCards() {
        val directory = Files.createTempDirectory("ui-doc-incremental").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val one = document("one")
            val two = document("two")
            assertTrue(store.publish(one, provenance, null, null, budget, emptySet()))
            assertEquals(setOf(one), store.read("session", setOf("one", "two"),
                budget, emptySet()).map { it.document }.toSet())
            assertTrue(store.publish(two, provenance, null, null, budget, emptySet()))
            assertEquals(setOf(one, two), store.read("session", setOf("one", "two"),
                budget, emptySet()).map { it.document }.toSet())
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

}

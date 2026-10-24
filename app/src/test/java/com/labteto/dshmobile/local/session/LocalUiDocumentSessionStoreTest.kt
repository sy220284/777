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

    @Test fun documentActionConsumedExactlyOnceAcrossTwoEntrypointsAndReopen() {
        val directory = Files.createTempDirectory("ui-doc-once").toFile()
        val registry = LocalSessionEventLogRegistry(directory, Json)
        try {
            val card = document("message")
            val action = UiDocumentActionRequest(card.documentId, card.revision,
                card.source, "send", mapOf("field" to "chosen"), true)
            val first = LocalUiDocumentSessionStore(registry)
            val second = LocalUiDocumentSessionStore(registry)
            var accepted = 0
            assertEquals(UiCommandReceipt.Accepted, first.submitActionOnce(action) {
                accepted++
                UiCommandReceipt.Accepted
            })
            assertEquals(UiCommandReceipt.Accepted, second.submitActionOnce(action.copy(
                fields = mapOf("field" to "other"))) {
                accepted++
                UiCommandReceipt.Accepted
            })
            assertEquals(1, accepted)
            // Neither navigation nor a new Session-store object creates another admission.
            assertEquals(UiCommandReceipt.Accepted,
                LocalUiDocumentSessionStore(registry).actionReceipt(action))
            val next = action.copy(revision = action.revision + 1L)
            assertEquals(UiCommandReceipt.Queued, second.submitActionOnce(next) {
                accepted++
                UiCommandReceipt.Queued
            })
            assertEquals(2, accepted)
        } finally { registry.get("session").close(); directory.deleteRecursively() }
    }

    @Test fun rejectedFormAdmissionCanBeRetriedWithoutLosingAcceptedDeduplication() {
        val dir = Files.createTempDirectory("ui-doc-retry").toFile()
        val registry = LocalSessionEventLogRegistry(dir, Json)
        try {
            val card = document("message")
            val request = UiDocumentActionRequest(card.documentId, card.revision,
                card.source, "send", emptyMap(), true)
            val owner = LocalUiDocumentSessionStore(registry)
            var attempts = 0
            assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.QUEUE_FULL),
                owner.submitActionOnce(request) {
                    attempts++
                    UiCommandReceipt.Rejected(UiCommandRejection.QUEUE_FULL)
                })
            assertEquals(null, owner.actionReceipt(request))
            assertEquals(UiCommandReceipt.Accepted, owner.submitActionOnce(request) {
                attempts++
                UiCommandReceipt.Accepted
            })
            assertEquals(UiCommandReceipt.Accepted, owner.submitActionOnce(request) {
                attempts++
                UiCommandReceipt.Accepted
            })
            assertEquals(2, attempts)
        } finally { registry.get("session").close(); dir.deleteRecursively() }
    }

    @Test fun interruptedDocumentActionIsNeverSilentlyDispatchedAgain() {
        val directory = Files.createTempDirectory("ui-doc-unknown").toFile()
        val registry = LocalSessionEventLogRegistry(directory, Json)
        try {
            val card = document("message")
            val action = UiDocumentActionRequest(card.documentId, card.revision,
                card.source, "send", emptyMap(), true)
            val store = LocalUiDocumentSessionStore(registry)
            assertThrows(IllegalStateException::class.java) {
                store.submitActionOnce(action) { throw IllegalStateException("power loss simulation") }
            }
            var repeats = 0
            assertEquals(UiCommandReceipt.OutcomeUnknown,
                LocalUiDocumentSessionStore(registry).submitActionOnce(action) {
                    repeats++
                    UiCommandReceipt.Accepted
                })
            assertEquals(0, repeats)
        } finally { registry.get("session").close(); directory.deleteRecursively() }
    }

    @Test fun failedOptionalCheckpointWriteDoesNotHideDurableHistory() {
        val directory = Files.createTempDirectory("ui-doc-checkpoint-write-failure").toFile()
        val registry = LocalSessionEventLogRegistry(directory, Json)
        try {
            val store = LocalUiDocumentSessionStore(registry)
            val first = document("first")
            assertTrue(store.publish(first, provenance, null, null, budget, emptySet()))
            // A nonempty directory at the sidecar destination makes atomic replacement fail
            // on every host, without depending on root/non-root permission behavior.
            val blocked = java.io.File(directory, "session.events.jsonl.projection-ui-document-window.json")
            assertTrue(blocked.mkdir())
            java.io.File(blocked, "occupied").writeText("block checkpoint replacement")
            assertEquals(first, store.read("session", setOf("first"), budget, emptySet()).single().document)
            assertTrue(blocked.deleteRecursively())
            // A fresh projection can still rebuild and persist from the original authority.
            assertEquals(first, LocalUiDocumentSessionStore(registry)
                .read("session", setOf("first"), budget, emptySet()).single().document)
            assertNotNull(registry.get("session").readProjectionCheckpoint("ui-document-window", 1))
        } finally { registry.get("session").close(); directory.deleteRecursively() }
    }

    @Test fun coldWindowCheckpointReplaysEditsAndBatchTombstonesFromOriginalLog() {
        val directory = Files.createTempDirectory("ui-doc-cold-window").toFile()
        val registry = LocalSessionEventLogRegistry(directory, Json)
        try {
            val store = LocalUiDocumentSessionStore(registry)
            val first = document("first")
            val second = document("second")
            assertTrue(store.publish(first, provenance, null, null, budget, emptySet()))
            assertTrue(store.publish(second, provenance, null, null, budget, emptySet()))
            val messages = setOf("first", "second")
            assertEquals(2, store.read("session", messages, budget, emptySet()).size)
            val log = registry.get("session")
            val checkpoint = log.readProjectionCheckpoint("ui-document-window", 1)!!
            assertEquals(log.latestSequence(), checkpoint.throughSequence)
            val edited = first.withUserEditedText("item_0", "updated after checkpoint")!!
            assertTrue(store.publish(edited, provenance, null, first, budget, emptySet()))
            val otherRegistry = LocalSessionEventLogRegistry(directory, Json)
            val reopened = LocalUiDocumentSessionStore(otherRegistry)
            val restored = reopened.read("session", messages, budget, emptySet())
            assertEquals(edited, restored.single { it.document.documentId == first.documentId }.document)
            assertTrue(store.deleteBatch(listOf(edited, second), budget, emptySet()))
            assertTrue(LocalUiDocumentSessionStore(registry).read("session", messages, budget, emptySet()).isEmpty())
            assertFalse(store.publish(first, provenance, null, null, budget, emptySet()))
            otherRegistry.get("session").close()
        } finally { registry.get("session").close(); directory.deleteRecursively() }
    }

    @Test fun corruptColdWindowIsRebuiltWithoutLosingCurrentEditsOrRestoringDeletedCards() {
        val directory = Files.createTempDirectory("ui-doc-corrupt-cold-window").toFile()
        val registry = LocalSessionEventLogRegistry(directory, Json)
        try {
            val store = LocalUiDocumentSessionStore(registry)
            val first = document("first")
            assertTrue(store.publish(first, provenance, null, null, budget, emptySet()))
            store.read("session", setOf("first"), budget, emptySet())
            registry.get("session").writeProjectionCheckpoint("ui-document-window", 1,
                registry.get("session").latestSequence(), "not-json", registry.get("session").resetGeneration)
            assertEquals(first, LocalUiDocumentSessionStore(registry)
                .read("session", setOf("first"), budget, emptySet()).single().document)
            assertTrue(store.delete(first, budget, emptySet()))
            assertTrue(LocalUiDocumentSessionStore(registry).read("session", setOf("first"), budget, emptySet()).isEmpty())
        } finally { registry.get("session").close(); directory.deleteRecursively() }
    }

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

    @Test fun userEditedTableCellPublishesThroughOriginalEventLogWithRevisionCas() {
        val directory = Files.createTempDirectory("ui-doc-table-edit").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val original = UiDocument(1, "table-card",
                UiDocumentSource("session", "message", 2, "profile", null), 0,
                "当前项目数据",
                listOf(UiDocumentNode.Table("rows", listOf("name", "value"),
                    listOf(listOf("risk", "unverified"), listOf("phase", "draft")))),
                emptyList(), "初始数据")
            assertTrue(store.publish(original, provenance, null, null, budget, emptySet()))
            val corrected = original.withUserEditedTableCell("rows", 0, 1, "user corrected")!!
            val marker = UiDocumentProvenance(null, "user-edited", false, UiDocumentOrigin.LOCAL)
            assertTrue(store.publish(corrected, marker, null, original, budget, emptySet()))
            val stale = original.withUserEditedTableCell("rows", 0, 1, "stale competing write")!!
            assertFalse(store.publish(stale, marker, null, original, budget, emptySet()))
            val reopened = LocalUiDocumentSessionStore(registry).read(
                "session", setOf("message"), budget, emptySet()).single()
            assertEquals(corrected, reopened.document)
            assertEquals(marker, reopened.provenance)
            assertTrue(store.delete(corrected, budget, emptySet()))
            assertFalse(store.publish(corrected.copy(revision = 2), marker, null,
                corrected, budget, emptySet()))
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun chartPointCorrectionsRoundTripWithCasAndTombstones() {
        val directory = Files.createTempDirectory("ui-doc-chart-edit").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val store = LocalUiDocumentSessionStore(registry)
            val original = UiDocument(1, "chart-card",
                UiDocumentSource("session", "message", 2, "profile", null), 0,
                "真实图表引用",
                listOf(UiDocumentNode.BarChart("bars", "请求量", "次",
                    listOf(UiChartPoint("成功", 3.0), UiChartPoint("失败", 1.0))),
                    UiDocumentNode.LineChart("trend", "请求趋势", "次",
                        listOf(UiChartPoint("昨天", 4.0), UiChartPoint("今天", 5.0)))),
                emptyList(), "初始图表")
            val user = UiDocumentProvenance(null, "user-edited", false, UiDocumentOrigin.LOCAL)
            assertTrue(store.publish(original, provenance, null, null, budget, emptySet()))
            val barEdit = original.withUserEditedChartPoint("bars", 1, 8.0)!!
            assertTrue(store.publish(barEdit, user, null, original, budget, emptySet()))
            val stale = original.withUserEditedChartPoint("trend", 0, 7.0)!!
            assertFalse(store.publish(stale, user, null, original, budget, emptySet()))
            val reopened = LocalUiDocumentSessionStore(registry)
            assertEquals(barEdit, reopened.read("session", setOf("message"), budget, emptySet()).single().document)
            val lineEdit = barEdit.withUserEditedChartPoint("trend", 0, 9.0)!!
            assertTrue(reopened.publish(lineEdit, user, null, barEdit, budget, emptySet()))
            assertEquals(lineEdit, LocalUiDocumentSessionStore(registry).read(
                "session", setOf("message"), budget, emptySet()).single().document)
            assertTrue(store.delete(lineEdit, budget, emptySet()))
            assertFalse(store.publish(lineEdit.copy(revision = 3), user, null, lineEdit, budget, emptySet()))
            assertNull(LocalUiDocumentSessionStore(registry)
                .latestDocument("session", "message", "chart-card", budget, emptySet()))
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

    @Test fun batchDeletionIsOneEventAllOrNothingAndSurvivesColdRestore() {
        val directory = Files.createTempDirectory("ui-doc-batch-delete").toFile()
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val owner = LocalUiDocumentSessionStore(registry)
            val one = document("one")
            val two = document("two")
            val three = document("three")
            for (doc in listOf(one, two, three))
                assertTrue(owner.publish(doc, provenance, null, null, budget, emptySet()))
            val log = registry.get("session")
            val before = log.latestSequence()
            assertFalse(owner.deleteBatch(listOf(one, two.copy(revision = 1)), budget, emptySet()))
            assertEquals(before, log.latestSequence())
            assertEquals(3, owner.read("session", setOf("one", "two", "three"), budget, emptySet()).size)
            assertTrue(owner.deleteBatch(listOf(one, two), budget, emptySet()))
            assertEquals(before + 1, log.latestSequence())
            assertEquals(UI_DOCUMENT_BATCH_DELETED_EVENT, log.latest(UI_DOCUMENT_BATCH_DELETED_EVENT)?.type)
            assertNull(owner.latestDocument("session", "one", one.documentId, budget, emptySet()))
            assertNull(owner.latestDocument("session", "two", two.documentId, budget, emptySet()))
            assertFalse(owner.publish(one.copy(revision = 1), provenance, null, one, budget, emptySet()))
            val restored = LocalUiDocumentSessionStore(registry)
            assertEquals(listOf(three), restored.read(
                "session", setOf("one", "two", "three"), budget, emptySet()).map { it.document })
            assertFalse(restored.deleteBatch(listOf(one, two), budget, emptySet()))
            registry.clearAndEvict(setOf("session"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun separateOwnersShareOneCompareAndAppendLease() {
        val directory = Files.createTempDirectory("ui-doc-owner-race").toFile()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val registry = LocalSessionEventLogRegistry(directory, Json)
            val first = LocalUiDocumentSessionStore(registry)
            val second = LocalUiDocumentSessionStore(registry)
            val original = document("shared")
            assertTrue(first.publish(original, provenance, null, null, budget, emptySet()))
            val ready = java.util.concurrent.CountDownLatch(2)
            val begin = java.util.concurrent.CountDownLatch(1)
            fun race(owner: LocalUiDocumentSessionStore, title: String) = executor.submit<Boolean> {
                ready.countDown()
                check(begin.await(5, java.util.concurrent.TimeUnit.SECONDS))
                owner.publish(original.copy(revision = 1, title = title), provenance,
                    null, original, budget, emptySet())
            }
            val a = race(first, "first owner")
            val b = race(second, "second owner")
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            begin.countDown()
            val results = listOf(a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                b.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, results.count { it })
            val restored = LocalUiDocumentSessionStore(registry)
                .latestDocument("session", "shared", original.documentId, budget, emptySet())
            assertEquals(1L, restored!!.document.revision)
            assertTrue(restored.document.title in setOf("first owner", "second owner"))
            assertEquals(1L, registry.get("session").latestSequence())
            registry.clearAndEvict(setOf("session"))
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
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

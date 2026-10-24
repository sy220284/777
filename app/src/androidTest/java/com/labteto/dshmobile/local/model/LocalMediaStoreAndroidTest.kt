package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite, isolated app-private fixtures; no provider requests or paid accounts. */
@RunWith(AndroidJUnit4::class)
class LocalMediaStoreAndroidTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "media-test-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, namespace).apply { mkdirs() }
    private val connections = mutableListOf<SQLiteDatabase>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory, null)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler).also { connections += it }
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$namespace-$name", mode)
    }
    private val directory = File(root, "media-studio").apply { mkdirs() }
    private val offer = LocalMediaCatalog.offers(listOf(LocalModelProfile("test", "gpt-6", "https://api.openai.com/v1")))
        .first { it.kind == LocalMediaKind.IMAGE }
    private fun result(id: String, time: Long = 1L, session: String = "session", file: String? = null) =
        LocalMediaResult(id, offer.model, offer.provider.name, "IMAGE", time, fileName = file,
            textOutput = if (file == null) "fixture" else null, sessionId = session)
    private fun store() = LocalMediaStore(context, json)
    private fun legacy(results: List<LocalMediaResult>) {
        File(directory, "results.json").writeText(json.encodeToString(LocalMediaResultList.serializer(), LocalMediaResultList(results)))
    }
    @After fun cleanup() {
        connections.filter { it.isOpen }.forEach { it.close() }
        listOf("local_media_migration", "local_media_results", "local_media_video_tasks").forEach {
            base.deleteSharedPreferences("$namespace-$it")
        }
        root.deleteRecursively()
    }

    @Test fun migrationFailureRollsBackRowsAndMarkerTogether() {
        val db = LocalMediaDatabase(context, json)
        db.writableDatabase.execSQL("CREATE TRIGGER fail_fixture BEFORE INSERT ON media WHEN NEW.id='bad' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        assertThrows(Exception::class.java) {
            db.migrate(listOf(LocalMediaRecord("ok", result = result("ok")), LocalMediaRecord("bad", result = result("bad"))))
        }
        assertFalse(db.migrated())
        assertEquals(null, db.get("ok"))
        db.writableDatabase.execSQL("DROP TRIGGER fail_fixture")
        db.migrate(listOf(LocalMediaRecord("ok", result = result("ok"))))
        assertTrue(db.migrated())
        assertEquals("ok", db.get("ok")?.result?.id)
    }

    @Test fun migrationArchivesSourcesAndDeletedResultsNeverReimportOnRestart() {
        legacy(listOf(result("old")))
        val first = store()
        assertEquals("old", first.page().results.single().id)
        assertTrue(File(directory, "migration-archive/results.json").isFile)
        assertFalse(File(directory, "results.json").exists())
        first.remove("old")
        assertTrue(store().page().results.isEmpty())
        assertTrue(File(directory, "migration-archive/results.json").isFile)
    }

    @Test fun missingMigratedDatabaseIsAnErrorInsteadOfEmptyPaidHistory() {
        legacy(listOf(result("old")))
        store().initialize()
        connections.filter { it.isOpen }.forEach { it.close() }
        assertTrue(File(root, "local_media_v1.db").delete())
        val reopened = store()
        assertThrows(IllegalStateException::class.java) { reopened.page() }
        assertTrue(reopened.recoveryErrors.value != null)
    }

    @Test fun deletionCommitsBeforeFileCleanupAndRetriesWithoutResurrection() {
        val obstacle = File(directory, "fixture.bin").apply { mkdirs() }
        File(obstacle, "child").writeText("fixture")
        legacy(listOf(result("old", file = obstacle.name)))
        val first = store()
        assertThrows(java.io.IOException::class.java) { first.remove("old") }
        assertTrue(first.page().results.isEmpty())
        assertTrue(first.recoveryErrors.value != null)
        File(obstacle, "child").delete()
        first.retryStorage()
        assertFalse(obstacle.exists())
        assertTrue(store().page().results.isEmpty())
    }

    @Test fun restartDistinguishesNotSentUnknownAndLocalSaveInterrupted() {
        val first = store()
        val prepared = first.beginCreation(offer, "session", "one")
        val submitted = first.beginCreation(offer, "session", "two")
        first.requestStarted(submitted.id)
        val saving = first.beginCreation(offer, "session", "three")
        first.requestStarted(saving.id)
        first.providerSucceeded(saving.id)
        val jobs = store().page().jobs.associateBy { it.id }
        assertEquals("NOT_SENT", jobs.getValue(prepared.id).errorCode)
        assertEquals(LocalMediaCreationPhase.OUTCOME_UNKNOWN, jobs.getValue(submitted.id).phase)
        assertEquals(LocalMediaCreationPhase.SAVE_FAILED, jobs.getValue(saving.id).phase)
        assertEquals(offer.profile.routeFingerprint(), jobs.getValue(submitted.id).routeFingerprint)
    }

    @Test fun receiptAndResultCommitTogetherAndLateWriterCannotResurrectDeletion() {
        val first = store()
        val job = first.beginCreation(offer, "session", "fixture")
        assertThrows(IllegalArgumentException::class.java) { first.remove(job.id) }
        val file = File(directory, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        first.requestStarted(job.id)
        first.providerSucceeded(job.id)
        first.addResult(offer, LocalMediaKind.IMAGE, "session", operationId = job.id, file = file)
        val reopened = store().page()
        assertEquals(job.id, reopened.results.single().id)
        assertEquals(LocalMediaCreationPhase.SUCCEEDED, reopened.jobs.single().phase)
        first.remove(job.id)
        assertTrue(first.page().jobs.isEmpty())
        assertTrue(first.page().results.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            first.addResult(offer, LocalMediaKind.IMAGE, "session", operationId = job.id, text = "late")
        }
        assertTrue(store().page().results.isEmpty())
    }

    @Test fun originalImageRedownloadKeepsSessionAndCannotReviveDeletedResult() {
        val media = store()
        val job = media.beginCreation(offer, "session", "image")
        media.requestStarted(job.id)
        media.providerSucceeded(job.id)
        val url = "https://images.example.org/original.png"
        media.addResult(offer, LocalMediaKind.IMAGE, "session",
            operationId = job.id, link = url)
        val downloaded = File(directory, "recovered.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(media.attachDownloadedImage(job.id, "another-session", url, downloaded))
        assertFalse(media.attachDownloadedImage(job.id, "session", "https://wrong.example.org", downloaded))
        assertTrue(media.attachDownloadedImage(job.id, "session", url, downloaded))
        val restored = store().result(job.id)
        assertEquals("recovered.png", restored?.fileName)
        assertEquals(null, restored?.remoteUrl)
        assertFalse(media.attachDownloadedImage(job.id, "session", url, downloaded))
        media.remove(job.id)
        val lateFile = File(directory, "late.png").apply { writeBytes(byteArrayOf(4)) }
        assertFalse(media.attachDownloadedImage(job.id, "session", url, lateFile))
        assertEquals(null, store().result(job.id))
    }

    @Test fun deletingRegeneratedResultDoesNotResurrectOldAudioOrDeleteOtherResults() {
        val old = result("original", 10L, file = "original.png")
        val revision = result("revision", 20L, file = "revision.png").copy(revisionOf = "original")
        val other = result("unrelated", 30L, file = "unrelated.png")
        listOf(old, revision, other).forEach { File(directory, it.fileName!!).writeBytes(byteArrayOf(7)) }
        legacy(listOf(old, revision, other))
        val first = store()
        assertEquals(3, first.page().results.size)
        first.removeRevisionFamily("revision")
        assertEquals(listOf("unrelated"), first.page().results.map { it.id })
        assertFalse(File(directory, "original.png").exists())
        assertFalse(File(directory, "revision.png").exists())
        assertTrue(File(directory, "unrelated.png").isFile)
        assertEquals(listOf("unrelated"), store().page().results.map { it.id })
    }

    @Test fun sharedOriginalAcrossSessionsIsNotDeletedUntilItsLastReferenceIsRemoved() {
        val shared = File(directory, "shared-original.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        legacy(listOf(
            result("first", 1L, session = "session-a", file = shared.name),
            result("second", 2L, session = "session-b", file = shared.name),
        ))
        val first = store()
        first.remove("first")
        assertTrue(shared.isFile)
        assertEquals("second", first.page().results.single().id)
        assertEquals("second", store().result("second")?.id)
        first.remove("second")
        assertFalse(shared.exists())
        assertTrue(store().page().results.isEmpty())
    }

    @Test fun removingRevisionFamilyKeepsUnrelatedResultReferencingSameFile() {
        val shared = File(directory, "shared-family.png").apply { writeBytes(byteArrayOf(4, 5)) }
        val original = result("original", 1L, file = shared.name)
        val revision = result("revision", 2L, file = shared.name).copy(revisionOf = original.id)
        val other = result("other", 3L, session = "second-session", file = shared.name)
        legacy(listOf(original, revision, other))
        val first = store()
        first.removeRevisionFamily("revision")
        assertTrue(shared.isFile)
        assertEquals("other", first.page().results.single().id)
        first.remove("other")
        assertFalse(shared.exists())
    }

    @Test fun indexedPagesKeepAllHistoryAndSeparateSessions() {
        legacy((1..130).map { result("r$it", it.toLong(), if (it % 2 == 0) "even" else "odd") })
        val first = store()
        assertEquals(50, first.page().results.size)
        assertTrue(first.page().hasMore)
        assertEquals(65, first.page("even", 100).results.size)
        assertTrue(first.page("even", 100).results.all { it.sessionId == "even" })
        assertEquals(130, first.page(limit = 200).results.size)
        assertFalse(first.page(limit = 200).hasMore)
        val db = LocalMediaDatabase(context, json)
        db.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM media WHERE deleted=0 AND session_id=? ORDER BY timestamp DESC, id LIMIT ?", arrayOf("even", "51")).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getString(3).contains("media_session"))
        }
    }
}

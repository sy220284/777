package com.labteto.dshmobile.local.model

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaDocumentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(file: File, legacy: () -> String? = { null }) = LocalMediaDocument(
        file, LocalMediaResultList.serializer(), Json, legacy,
    )
    private val existing = LocalMediaResultList(listOf(LocalMediaResult(
        "paid-image", "image-01", "MINIMAX", "IMAGE", 100L, remoteUrl = "https://cdn.example.com/a.png",
    )))

    @Test fun validLegacyIndexMigratesOnceAndPrimaryBackupRecoverPaidResult() {
        val file = File(temporary.root, "results.json")
        val raw = Json.encodeToString(LocalMediaResultList.serializer(), existing)
        assertEquals(existing, document(file) { raw }.readOrNull(::LocalMediaResultList))
        assertTrue(file.isFile)
        file.writeText("broken-primary")
        val recovered = document(file) { "broken-legacy-must-not-be-used" }
        assertEquals(existing, recovered.readOrNull(::LocalMediaResultList))
        assertNull(recovered.recoveryError)
        assertEquals(existing, document(file).readOrNull(::LocalMediaResultList))
    }

    @Test fun damagedLegacyNeverCreatesEmptyAuthoritativeIndex() {
        val file = File(temporary.root, "results.json")
        repeat(2) {
            val store = document(file) { "broken-legacy" }
            assertNull(store.readOrNull(::LocalMediaResultList))
            assertNotNull(store.recoveryError)
            assertThrows(IllegalStateException::class.java) { store.write(existing) }
            assertTrue(!file.exists())
        }
    }

    @Test fun corruptPrimaryAndBackupRemainBlockedAfterRestartAndPreserveOriginalBytes() {
        val file = File(temporary.root, "results.json").apply { writeText("broken-primary") }
        File(temporary.root, "results.json.bak").writeText("broken-backup")
        repeat(2) {
            val store = document(file)
            assertNull(store.readOrNull(::LocalMediaResultList))
            assertNotNull(store.recoveryError)
            assertThrows(IllegalStateException::class.java) { store.write(existing) }
        }
        val retained = temporary.root.listFiles().orEmpty()
            .filter { it.name.endsWith(".quarantine") }.map { it.readText() }
        assertTrue("broken-primary" in retained)
        assertTrue("broken-backup" in retained)
    }

    @Test fun officialMiniMaxWireVideoStatesMapToSharedLifecycle() {
        assertEquals("Queueing", normalizeMiniMaxVideoState("Preparing"))
        assertEquals("Queueing", normalizeMiniMaxVideoState("Queueing"))
        assertEquals("Processing", normalizeMiniMaxVideoState("Processing"))
        assertEquals("Success", normalizeMiniMaxVideoState("Success"))
        assertEquals("Failed", normalizeMiniMaxVideoState("Fail"))
        assertThrows(IllegalStateException::class.java) { normalizeMiniMaxVideoState("not-a-status") }
    }
}

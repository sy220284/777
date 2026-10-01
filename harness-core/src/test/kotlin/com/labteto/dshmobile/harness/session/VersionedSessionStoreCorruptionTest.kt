package com.labteto.dshmobile.harness.session

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedSessionStoreCorruptionTest {
    @Test
    fun listReportsCorruptSessionInsteadOfSilentlyDroppingIt() {
        val root = Files.createTempDirectory("session-store-corrupt").toFile()
        try {
            val failures = mutableListOf<String>()
            val store = VersionedSessionStore(
                root = root,
                json = Json,
                onCorruptSkipped = { id, _ -> failures += id },
            )
            store.write("good", buildJsonObject { put("title", "ok") })
            File(root, "bad.json").writeText("{broken")
            File(root, "bad.json.bak").writeText("{also-broken")

            val listed = store.list()

            assertEquals(listOf("good"), listed.map { it.document.id })
            assertTrue("bad" in failures)
        } finally {
            root.deleteRecursively()
        }
    }
}

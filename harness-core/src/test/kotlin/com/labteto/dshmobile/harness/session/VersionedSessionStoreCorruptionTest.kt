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

    @Test
    fun eventProjectionSidecarsNeverBecomeSessionsEvenAfterNestedProjectionNames() {
        val root = Files.createTempDirectory("session-projection-sidecars").toFile()
        try {
            val id = "83b4f37f-fe6d-4213-97b6-8ca2251f825a"
            val store = VersionedSessionStore(root, Json)
            store.write(id, buildJsonObject { put("title", "原始对话") })
            val sidecar = File(root, "$id.events.jsonl.projection-work.agent-team.json")
                .apply { writeText("""{"version":1,"identity":"projection","throughSequence":1,"payload":"{}"}""") }
            val nested = File(
                root,
                "$id.events.jsonl.projection-work.agent-team.events.jsonl.projection-work.agent-team.json",
            ).apply { writeText("{}") }
            val excessivelyNested = File(
                root,
                "$id" + ".events.jsonl.projection-work.agent-team".repeat(4) + ".json",
            ).apply { writeText("{}") }
            File(root, "$id.events.jsonl").writeText("event data")
            File(root, "$id.backup.json").writeText("{}")
            File(root, "$id.checkpoint-v0.json").writeText("{}")
            File(root, "unrelated invalid id.json").writeText("{}")

            assertEquals(listOf(id), store.ids())
            assertEquals(listOf(id), store.list().map { it.document.id })
            assertEquals(id, store.read(id)?.document?.id)
            assertTrue(sidecar.isFile)
            assertTrue(nested.isFile)
            assertTrue(excessivelyNested.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}

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
    fun legacyAndWrappedPayloadIdentitiesCannotOpenAnotherSession() {
        val root = Files.createTempDirectory("session-payload-identity").toFile()
        try {
            val store = VersionedSessionStore(root, Json)
            File(root, "legacy.json").writeText("""{"id":"other","title":"错误旧会话"}""")
            File(root, "wrapped.json").writeText(
                """{"formatVersion":1,"id":"wrapped","updatedAt":1,"payload":{"id":"other"}}""",
            )
            File(root, "invalid-version.json").writeText(
                """{"formatVersion":"broken","id":"invalid-version","payload":{}}""",
            )
            assertTrue(runCatching { store.read("legacy") }.isFailure)
            assertTrue(runCatching { store.read("wrapped") }.isFailure)
            assertTrue(runCatching { store.read("invalid-version") }.isFailure)
            assertTrue(runCatching {
                store.write("new", buildJsonObject { put("id", "other") })
            }.isFailure)
            assertTrue(!File(root, "new.json").exists())
            File(root, "before-id.json").writeText("""{"title":"没有编号的旧会话","messages":[]}""")
            assertEquals("before-id", store.read("before-id")?.document?.id)
            File(root, "blank-id.json").writeText("""{"id":"","title":"空编号旧会话","messages":[]}""")
            assertEquals("blank-id", store.read("blank-id")?.document?.id)
            assertTrue("blank-id" in store.ids())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedPayloadRecoversOnlyFromCorrectlyIdentifiedBackup() {
        val root = Files.createTempDirectory("session-payload-backup").toFile()
        try {
            val store = VersionedSessionStore(root, Json)
            store.write("one", buildJsonObject { put("id", "one"); put("title", "原会话") })
            File(root, "one.json").writeText(
                """{"formatVersion":1,"id":"one","updatedAt":2,"payload":{"id":"other"}}""",
            )
            val recovered = store.read("one")!!
            assertTrue(recovered.recovered)
            assertEquals("\"one\"", recovered.document.payload["id"].toString())
            assertTrue(File(root, "one.backup.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

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
            store.write("bad", buildJsonObject { put("title", "before-corruption") })
            File(root, "bad.json").writeText("{broken")
            File(root, "bad.backup.json").writeText("{also-broken")

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
            store.write("ordinary-chat", buildJsonObject { put("title", "其他正常聊天") })
            store.write("older.events.jsonl", buildJsonObject { put("title", "含日志标记的合法旧会话") })
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

            assertEquals(setOf(id, "ordinary-chat", "older.events.jsonl"), store.ids().toSet())
            assertEquals(setOf(id, "ordinary-chat", "older.events.jsonl"),
                store.list().map { it.document.id }.toSet())
            assertEquals(id, store.read(id)?.document?.id)
            assertEquals("ordinary-chat", store.read("ordinary-chat")?.document?.id)
            assertEquals("older.events.jsonl", store.read("older.events.jsonl")?.document?.id)
            assertTrue(sidecar.isFile)
            assertTrue(nested.isFile)
            assertTrue(excessivelyNested.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coldMigrationUsesDocumentIdentityAndDoesNotAdoptProjectionFiles() {
        val root = Files.createTempDirectory("session-catalog-migrate").toFile()
        try {
            val id = "legacy-chat"
            File(root, "$id.json").writeText("""{"id":"legacy-chat","title":"旧聊天","usageMode":"CHAT"}""")
            File(root, "future.json").writeText(
                """{"formatVersion":99,"id":"future","updatedAt":1,"payload":{}}""",
            )
            val projection = File(root, "$id.events.jsonl.projection-work.agent-team.json")
                .apply { writeText("""{"version":1,"identity":"hash","throughSequence":2,"payload":"{}"}""") }
            File(root, "unrelated.json").writeText("""{"title":"其他资料","body":"文字"}""")
            File(root, "with.backup.json").writeText(
                """{"formatVersion":1,"id":"with.backup","updatedAt":1,"payload":{}}""",
            )
            File(root, "archived.backup.json").writeText(
                """{"formatVersion":1,"id":"archived","updatedAt":1,"payload":{}}""",
            )
            val store = VersionedSessionStore(root, Json)
            assertEquals(setOf(id, "future", "with.backup", "archived"), store.ids().toSet())
            assertEquals("旧聊天", store.read(id)?.document?.payload?.get("title")?.toString()?.trim('"'))
            assertTrue(projection.isFile)
            assertTrue(File(root, ".session-catalog.v1").isFile)
            assertEquals(setOf(id, "future", "with.backup", "archived"),
                VersionedSessionStore(root, Json).ids().toSet())
            assertEquals("with.backup", store.read("with.backup")?.document?.id)
            assertTrue(store.read("archived")!!.recovered)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun registrySurvivesDeletionAndCanRecoverFromCorruptionWithoutLosingSessions() {
        val root = Files.createTempDirectory("session-catalog-recover").toFile()
        try {
            val store = VersionedSessionStore(root, Json)
            store.write("one", buildJsonObject { put("title", "one") })
            store.write("two", buildJsonObject { put("title", "two") })
            assertEquals(setOf("one", "two"), store.ids().toSet())
            assertTrue(store.delete("one"))
            assertEquals(listOf("two"), store.ids())
            val backup = File(root, ".session-catalog.backup.v1")
            assertTrue(backup.isFile)
            File(root, ".session-catalog.v1").writeText("{broken")
            val reopenedDirectory = Files.createTempDirectory("session-catalog-reload").toFile()
            try {
                // A separate root simulates a fresh process, including persisted catalog damage.
                root.copyRecursively(reopenedDirectory, overwrite = true)
                val recovered = VersionedSessionStore(reopenedDirectory, Json)
                assertEquals(listOf("two"), recovered.ids())
                assertTrue(recovered.read("two") != null)
            } finally {
                reopenedDirectory.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun backupOnlySessionRestoresAndRemainsVisibleAfterColdMigration() {
        val root = Files.createTempDirectory("session-backup-only").toFile()
        try {
            val store = VersionedSessionStore(root, Json)
            store.write("recover", buildJsonObject { put("title", "备份") })
            assertTrue(File(root, "recover.json").delete())
            assertEquals(listOf("recover"), VersionedSessionStore(root, Json).ids())
            assertTrue(VersionedSessionStore(root, Json).read("recover")!!.recovered)
            assertTrue(File(root, "recover.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directReadRegistersLateLegacySessionAndRejectsUnrelatedProjection() {
        val root = Files.createTempDirectory("session-catalog-late-legacy").toFile()
        try {
            val store = VersionedSessionStore(root, Json)
            store.write("existing", buildJsonObject { put("title", "已有") })
            File(root, "late.json").writeText(
                """{"id":"late","title":"补迁旧会话","usageMode":"CHAT"}""",
            )
            val projectionId = "existing.events.jsonl.projection-work.agent-team"
            File(root, "$projectionId.json").writeText(
                """{"version":1,"identity":"hash","throughSequence":1,"payload":"{}"}""",
            )
            assertEquals(listOf("existing"), store.ids())
            assertEquals("late", store.read("late")?.document?.id)
            assertEquals(setOf("existing", "late"), store.ids().toSet())
            // A direct read of a stray derived JSON file must not put it in the catalog.
            assertTrue(store.read(projectionId) != null)
            assertEquals(setOf("existing", "late"), store.ids().toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedWrappedDocumentCannotBeAcceptedForAnotherSession() {
        val root = Files.createTempDirectory("session-catalog-identity-mismatch").toFile()
        try {
            val store = VersionedSessionStore(root, Json)
            store.write("correct", buildJsonObject { put("title", "原会话") })
            File(root, "wrong.json").writeText(
                """{"formatVersion":1,"id":"correct","updatedAt":2,"payload":{"title":"错误归属"}}""",
            )
            assertTrue(runCatching { store.read("wrong") }.isFailure)
            assertEquals(listOf("correct"), store.ids())
        } finally {
            root.deleteRecursively()
        }
    }
}

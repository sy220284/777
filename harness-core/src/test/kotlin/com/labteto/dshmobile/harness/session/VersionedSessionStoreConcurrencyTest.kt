package com.labteto.dshmobile.harness.session

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedSessionStoreConcurrencyTest {
    @Test
    fun backupRecoveryCannotOverwriteAConcurrentNewerWrite() {
        val root = Files.createTempDirectory("store-recovery-write").toFile()
        val entered = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val migrationsCalled = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            root.resolve("s.json").writeText("broken")
            root.resolve("s.backup.json").writeText(
                """{"formatVersion":0,"id":"s","updatedAt":1,"payload":{"value":"old"}}""",
            )
            val migrations = SessionMigrationRegistry().apply {
                register(0) { payload ->
                    if (migrationsCalled.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                    payload
                }
            }
            val store = VersionedSessionStore(root, Json, migrations)
            val reader = pool.submit<SessionLoadResult?> { store.read("s") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val writer = pool.submit {
                writerStarted.countDown()
                store.write("s", buildJsonObject { put("value", "new") })
            }
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS))
            val overtookRecovery = try {
                writer.get(200, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) { false }
            assertFalse("writer must wait for the recovery transaction", overtookRecovery)
            release.countDown()
            assertTrue(reader.get(2, TimeUnit.SECONDS)!!.recovered)
            writer.get(2, TimeUnit.SECONDS)
            assertEquals("new", store.read("s")!!.document.payload["value"]!!.jsonPrimitive.content)
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test
    fun separateStoresRegisterConcurrentlyWithoutLosingSessionIdentities() {
        val root = Files.createTempDirectory("session-catalog-concurrent").toFile()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val stores = List(4) { VersionedSessionStore(root, Json) }
            val tasks = stores.mapIndexed { worker, store ->
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(20) { index ->
                        store.write("session-$worker-$index", buildJsonObject { put("worker", worker) })
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            val expected = (0 until 4).flatMap { worker ->
                (0 until 20).map { index -> "session-$worker-$index" }
            }.toSet()
            val reopened = VersionedSessionStore(root, Json)
            assertEquals(expected, reopened.ids().toSet())
            assertTrue(expected.all { reopened.read(it) != null })
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}

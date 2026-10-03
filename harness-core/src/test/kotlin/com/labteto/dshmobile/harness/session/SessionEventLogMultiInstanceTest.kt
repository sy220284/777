package com.labteto.dshmobile.harness.session

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventLogMultiInstanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun liveInstancesOnSamePathNeverReuseCommittedSequence() {
        val root = createTempDir(prefix = "session-log-multi-instance-")
        try {
            val file = File(root, "session.events.jsonl")
            val first = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            val second = SessionEventLog(file, json, maxBytes = 4_096, clock = { 2L })

            assertEquals(0L, first.append("test/first", buildJsonObject { put("value", 1) }).sequence)
            assertEquals(1L, second.append("test/second", buildJsonObject { put("value", 2) }).sequence)
            assertEquals(2L, first.append("test/third", buildJsonObject { put("value", 3) }).sequence)

            assertEquals(listOf(0L, 1L, 2L), first.snapshot().map(SessionEvent::sequence))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearRebasesOtherLiveInstanceBeforeItsNextAppend() {
        val root = createTempDir(prefix = "session-log-clear-instance-")
        try {
            val file = File(root, "session.events.jsonl")
            val first = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            first.append("test/old", buildJsonObject { put("value", 1) })
            val stale = SessionEventLog(file, json, maxBytes = 4_096, clock = { 2L })
            first.append("test/old-2", buildJsonObject { put("value", 2) })

            first.clear()
            val fresh = stale.append("test/fresh", buildJsonObject { put("value", 3) })

            assertEquals(0L, fresh.sequence)
            assertEquals(listOf(0L), stale.snapshot().map(SessionEvent::sequence))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manyLiveInstancesStayStrictlyMonotonicAcrossRotations() {
        val root = createTempDir(prefix = "session-log-many-instances-")
        try {
            val file = File(root, "session.events.jsonl")
            val logs = List(8) { index ->
                SessionEventLog(file, json, maxBytes = 700, clock = { index.toLong() })
            }

            repeat(1_000) { index ->
                val event = logs[index % logs.size].append(
                    "test/stress",
                    buildJsonObject {
                        put("index", index)
                        put("payload", "x".repeat(48))
                    },
                )
                assertEquals(index.toLong(), event.sequence)
            }

            val snapshot = logs.last().snapshot()
            assertEquals(1_000, snapshot.size)
            assertEquals((0L until 1_000L).toList(), snapshot.map(SessionEvent::sequence))
            assertEquals(1_000, snapshot.map(SessionEvent::sequence).toSet().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentWritersStayMonotonicAndLosslessAcrossRotations() {
        val root = createTempDir(prefix = "session-log-concurrent-writers-")
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(8)
        val logs = List(8) { index ->
            SessionEventLog(
                file = File(root, "session.events.jsonl"),
                json = json,
                maxBytes = 1_024,
                clock = { index.toLong() },
            )
        }
        try {
            val writesPerWorker = 250
            val futures = logs.mapIndexed { writer, log ->
                pool.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "并发写入启动超时" }
                    repeat(writesPerWorker) { local ->
                        log.append(
                            "test/concurrent-stress",
                            buildJsonObject {
                                put("writer", writer)
                                put("local", local)
                                put("payload", "x".repeat(64))
                            },
                        )
                    }
                }
            }

            assertTrue("并发写线程未全部就绪", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }

            val expected = logs.size * writesPerWorker
            val snapshot = logs.first().snapshot()
            assertEquals(expected, snapshot.size)
            assertEquals((0L until expected.toLong()).toList(), snapshot.map(SessionEvent::sequence))
            assertEquals(expected, snapshot.map(SessionEvent::sequence).toSet().size)
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            logs.forEach(SessionEventLog::close)
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedClearFromDifferentInstancesNeverLetsStaleCountersLeakBack() {
        val root = createTempDir(prefix = "session-log-chaotic-clear-")
        try {
            val file = File(root, "session.events.jsonl")
            val logs = List(8) { index ->
                SessionEventLog(file, json, maxBytes = 700, clock = { index.toLong() })
            }

            repeat(25) { cycle ->
                logs[cycle % logs.size].clear()
                logs.forEachIndexed { index, log ->
                    assertEquals(
                        index.toLong(),
                        log.append(
                            "test/after-clear",
                            buildJsonObject {
                                put("cycle", cycle)
                                put("writer", index)
                            },
                        ).sequence,
                    )
                }
                assertEquals(
                    (0L until logs.size.toLong()).toList(),
                    logs[(cycle + 1) % logs.size].snapshot().map(SessionEvent::sequence),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun latestSequenceTracksOtherLiveInstancesAndClear() {
        val root = createTempDir(prefix = "session-log-latest-sequence-")
        try {
            val file = File(root, "session.events.jsonl")
            val first = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            val second = SessionEventLog(file, json, maxBytes = 4_096, clock = { 2L })

            assertEquals(-1L, first.latestSequence())
            assertEquals(0L, second.append("test/one", buildJsonObject { put("value", 1) }).sequence)
            assertEquals(0L, first.latestSequence())

            assertEquals(1L, first.append("test/two", buildJsonObject { put("value", 2) }).sequence)
            assertEquals(1L, second.latestSequence())

            first.clear()
            assertEquals(-1L, second.latestSequence())
        } finally {
            root.deleteRecursively()
        }
    }

}

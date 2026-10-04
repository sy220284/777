package com.labteto.dshmobile.local

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSessionRuntimeRegistryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun liveAutomationOwnerPreventsGenericCrashTailRepair() = runTest {
        val log = LocalSessionEventLog(
            File(temporary.root, "session-a.events.jsonl"),
            json,
            sessionId = "session-a",
        )
        log.append("turn/start", buildJsonObject { put("automation", true) })

        val lease = LocalSessionRuntimeRegistry.acquire(
            "session-a",
            LocalSessionRuntimeKind.AUTOMATION_CHAT,
        )
        try {
            assertFalse(log.repairInterruptedTail().repaired)
            assertNull(log.latest("turn/end"))
        } finally {
            lease.close()
        }

        assertTrue(log.repairInterruptedTail().repaired)
        assertNotNull(log.latest("turn/end"))
    }

    @Test
    fun automationRecoveryCanForceRepairItsOwnPreviousTail() = runTest {
        val log = LocalSessionEventLog(
            File(temporary.root, "session-b.events.jsonl"),
            json,
            sessionId = "session-b",
        )
        log.append("turn/start", buildJsonObject { put("automation", true) })

        val lease = LocalSessionRuntimeRegistry.acquire(
            "session-b",
            LocalSessionRuntimeKind.AUTOMATION_WORK,
        )
        try {
            assertTrue(log.repairInterruptedTail(force = true).repaired)
            assertNotNull(log.latest("turn/end"))
        } finally {
            lease.close()
        }
    }
}

package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.tools.LocalToolActivity
import com.labteto.dshmobile.local.tools.LocalToolActivityKind
import com.labteto.dshmobile.local.tools.LocalToolActivityPhase
import org.junit.Assert.*
import org.junit.Test

class LocalWorkRunCpuDemandTest {
    private fun tool(id: String, phase: LocalToolActivityPhase, sequence: Long = 11L) =
        LocalToolActivity(id, "read", LocalToolActivityKind.FILE, phase, declaredSequence = sequence)

    @Test fun onlyWaitingForUserReleasesCpuButRealParallelWorkKeepsIt() {
        val question = tool("question", LocalToolActivityPhase.RUNNING)
        val reading = tool("read", LocalToolActivityPhase.RUNNING)
        assertFalse(workRunNeedsCpu(setOf("question"), listOf(question), 10L))
        assertTrue(workRunNeedsCpu(setOf("question"), listOf(question, reading), 10L))
        assertFalse(workRunNeedsCpu(setOf("question"), listOf(question,
            reading.copy(phase = LocalToolActivityPhase.COMPLETED)), 10L))
        assertTrue(workRunNeedsCpu(emptySet(), emptyList(), 10L))
    }

    @Test fun approvalAndQueuedFutureToolsDoNotKeepCpuAwake() {
        assertFalse(workRunNeedsCpu(setOf("approve"), listOf(
            tool("approve", LocalToolActivityPhase.DECLARED),
            tool("later", LocalToolActivityPhase.DECLARED),
            tool("old", LocalToolActivityPhase.RUNNING, 5L),
        ), 10L))
    }
}

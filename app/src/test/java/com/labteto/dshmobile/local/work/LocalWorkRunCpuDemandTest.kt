package com.labteto.dshmobile.local.work

import org.junit.Assert.*
import org.junit.Test

class LocalWorkRunCpuDemandTest {
    @Test fun waitingQuestionReleasesCpuButOtherExecutingBodyKeepsIt() {
        assertFalse(workRunNeedsCpu(setOf("question"), setOf("question")))
        assertTrue(workRunNeedsCpu(setOf("question"), setOf("question", "read")))
        assertFalse(workRunNeedsCpu(setOf("question"), setOf("question")))
        assertTrue(workRunNeedsCpu(emptySet(), emptySet()))
    }

    @Test fun approvalAndQueuedOrSettledToolsDoNotNeedCpu() {
        // The completed read's durable batch receipt can still be pending; it is not a live body.
        assertFalse(workRunNeedsCpu(setOf("approve"), emptySet()))
    }
}

package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolBatchDispatchPlanTest {
    @Test fun mixedReadAndWriteBatchesPreserveEverySideEffectBarrier() {
        val tools = listOf("read", "grep", "write", "glob", "read", "bash", "read")
        val groups = planLocalToolBatch(tools.size) { index ->
            tools[index] in setOf("read", "grep", "glob")
        }
        assertEquals(
            listOf(
                LocalToolDispatchGroup(0, 2, true),
                LocalToolDispatchGroup(2, 3, false),
                LocalToolDispatchGroup(3, 5, true),
                LocalToolDispatchGroup(5, 6, false),
                LocalToolDispatchGroup(6, 7, false),
            ),
            groups,
        )
        assertEquals((0 until tools.size).toList(),
            groups.flatMap { it.startInclusive until it.endExclusive })
    }

    @Test fun allParallelCallsRemainParallelButUnreviewedWritesNeverBecomeParallel() {
        assertEquals(
            listOf(LocalToolDispatchGroup(0, 4, true)),
            planLocalToolBatch(4) { true },
        )
        val serial = planLocalToolBatch(3) { false }
        assertTrue(serial.all { !it.parallel && it.endExclusive == it.startInclusive + 1 })
        assertTrue(planLocalToolBatch(0) { true }.isEmpty())
    }
}

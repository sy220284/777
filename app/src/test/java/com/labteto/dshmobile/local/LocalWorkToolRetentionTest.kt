package com.labteto.dshmobile.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkToolRetentionTest {
    @Test
    fun smallWorkToolResultStaysInline() {
        val source = "短结果".repeat(100)

        val retained = retainWorkToolResultForModel(source)

        assertFalse(retained.truncated)
        assertTrue(retained.text == source)
    }

    @Test
    fun largeWorkToolResultBecomesSmallRecoverablePreviewWithoutBrokenUnicode() {
        val source = "开头😀汉字".repeat(1_200) + "-结尾"

        val retained = retainWorkToolResultForModel(source)

        assertTrue(retained.truncated)
        assertTrue(retained.omittedBytes > 0)
        assertTrue(retained.text.length <= 1_100)
        assertFalse(retained.text.contains("\uFFFD"))
        assertTrue(retained.text.contains("工具结果过长"))
    }
}

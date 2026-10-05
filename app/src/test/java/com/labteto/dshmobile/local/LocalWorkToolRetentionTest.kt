package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.retainWorkToolResultForModel
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
        assertTrue(retained.text.length <= 4_200)
        assertFalse(retained.text.contains("\uFFFD"))
        assertTrue(retained.text.contains("工具结果过长"))
    }

    @Test
    fun defaultRecoveryPageStaysVerbatimInWorkRetention() {
        val source = "a".repeat(LocalToolOutputStore.DEFAULT_READ_BYTES)

        val retained = retainWorkToolResultForModel(source)

        assertFalse(retained.truncated)
        assertTrue(retained.text == source)
    }
}

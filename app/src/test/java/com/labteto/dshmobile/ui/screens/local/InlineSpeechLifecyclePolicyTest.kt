package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSpeechLifecyclePolicyTest {
    @Test
    fun android16KeepsExistingInlinePlayerBackgroundBehavior() {
        assertFalse(shouldSuspendInlineSpeechOnBackground(36))
    }

    @Test
    fun android17SuspendsInvisibleInlinePlayback() {
        assertTrue(shouldSuspendInlineSpeechOnBackground(37))
        assertTrue(shouldSuspendInlineSpeechOnBackground(38))
    }
}

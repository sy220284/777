package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSettingsRequestGateTest {
    @Test
    fun newerRequestInvalidatesOlderRequestOnSameChannel() {
        val gate = RemoteSettingsRequestGate()
        val first = gate.capture("project")
        val second = gate.capture("project")

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun channelsAdvanceIndependently() {
        val gate = RemoteSettingsRequestGate()
        val project = gate.capture("project")
        val models = gate.capture("models")

        assertTrue(gate.isCurrent(project))
        assertTrue(gate.isCurrent(models))
    }

    @Test
    fun resetInvalidatesEveryOutstandingRequest() {
        val gate = RemoteSettingsRequestGate()
        val project = gate.capture("project")
        val models = gate.capture("models")

        gate.reset()

        assertFalse(gate.isCurrent(project))
        assertFalse(gate.isCurrent(models))
    }
}

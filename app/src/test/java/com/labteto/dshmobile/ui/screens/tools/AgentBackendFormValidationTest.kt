package com.labteto.dshmobile.ui.screens.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBackendFormValidationTest {
    private val validPin = "a".repeat(64)

    @Test fun validTlsAuthorizationAccepted() {
        val errors = validateAgentBackendForm("agent.example.com", "8443", validPin, "secret_123", true)
        assertTrue(errors.valid)
        assertNull(errors.host)
        assertNull(errors.port)
        assertNull(errors.pin)
        assertNull(errors.token)
        assertNull(errors.consent)
    }

    @Test fun invalidInputsAreExplainedAtTheirOwnFields() {
        val errors = validateAgentBackendForm("", "0", "BAD", "contains space", false)
        assertFalse(errors.valid)
        assertTrue(errors.host!!.contains("主机"))
        assertTrue(errors.port!!.contains("1–65535"))
        assertTrue(errors.pin!!.contains("64"))
        assertTrue(errors.token!!.contains("空格"))
        assertTrue(errors.consent!!.contains("确认"))
    }

    @Test fun hostAndPortAreValidatedLikeTheRuntime() {
        assertFalse(validateAgentBackendForm("agent.example.com/path", "8443", validPin, "token", true).valid)
        assertFalse(validateAgentBackendForm("host", "65536", validPin, "token", true).valid)
        assertFalse(validateAgentBackendForm(" host", "443", validPin, "token", true).valid)
        assertNull(validateAgentBackendForm("host", "65535", validPin.uppercase(), "token", true).pin)
    }

    @Test fun tokenCannotContainWhitespaceOrNonAsciiAndHasMaxLength() {
        assertTrue(validateAgentBackendForm("host", "443", validPin, "a".repeat(4096), true).valid)
        assertFalse(validateAgentBackendForm("host", "443", validPin, "a".repeat(4097), true).valid)
        assertFalse(validateAgentBackendForm("host", "443", validPin, "中文", true).valid)
        assertFalse(validateAgentBackendForm("host", "443", validPin, "line\nfeed", true).valid)
        assertFalse(validateAgentBackendForm("host", "443", validPin, " ", true).valid)
    }

    @Test fun validPortFieldHasNoError() {
        val errors = validateAgentBackendForm("host", "1", validPin, "token", true)
        assertEquals(null, errors.port)
    }
}

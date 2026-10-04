package com.labteto.dshmobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAsyncRequestRegistryTest {
    @Test fun resetRejectsAbaEvenWhenHostAndSessionReturn() {
        val registry = SessionAsyncRequestRegistry()
        val old = registry.capture("catalog", "host", "session")
        registry.reset()
        assertFalse(old.isCurrent({ "host" }, { "session" }))
        assertTrue(registry.capture("catalog", "host", "session").isCurrent({ "host" }, { "session" }))
    }
    @Test fun latestRequestWinsWithoutInvalidatingAnotherChannel() {
        val registry = SessionAsyncRequestRegistry()
        val old = registry.capture("catalog", "host", "session")
        val transcript = registry.capture("transcript", "host", "session")
        val latest = registry.capture("catalog", "host", "session")
        assertFalse(old.isCurrent({ "host" }, { "session" }))
        assertTrue(latest.isCurrent({ "host" }, { "session" }))
        assertTrue(transcript.isCurrent({ "host" }, { "session" }))
        registry.invalidate("transcript")
        assertFalse(transcript.isCurrent({ "host" }, { "session" }))
    }
}

package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import com.labteto.dshmobile.harness.agent.AgentModelRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSessionModelRouteProjectionTest {
    @Test
    fun eventRouteRepairsSnapshotThatWasNotFlushed() {
        val snapshotRoute = AgentModelRoute(
            provider = "openai-compatible",
            baseUrl = "https://example.com/v1",
            model = "old",
            protocol = AgentModelProtocol.OPENAI_CHAT,
        )
        val eventRoute = AgentModelRoute(
            provider = "deepseek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-v4-pro",
            protocol = AgentModelProtocol.ANTHROPIC_MESSAGES,
        )
        val event = LocalSessionEventLog.Event(
            sequence = 9,
            type = LOCAL_MODEL_ROUTE_EVENT_TYPE,
            createdAt = 1,
            data = encodeLocalModelRouteEvent(eventRoute),
        )

        val recovered = recoverSessionModelRoute(
            snapshot = LocalHarnessSession(id = "s", modelRoute = snapshotRoute),
            latestRouteEvent = event,
            fallbackBaseUrl = "https://fallback.example/v1",
            fallbackModel = "fallback",
        )

        assertEquals(eventRoute, recovered)
    }

    @Test
    fun invalidEventFallsBackToPersistedSnapshotRoute() {
        val snapshotRoute = AgentModelRoute(
            provider = "deepseek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            protocol = AgentModelProtocol.ANTHROPIC_MESSAGES,
        )
        val event = LocalSessionEventLog.Event(
            sequence = 10,
            type = LOCAL_MODEL_ROUTE_EVENT_TYPE,
            createdAt = 1,
            data = kotlinx.serialization.json.buildJsonObject {
                put("model", "")
            },
        )

        val recovered = recoverSessionModelRoute(
            snapshot = LocalHarnessSession(id = "s", modelRoute = snapshotRoute),
            latestRouteEvent = event,
            fallbackBaseUrl = "https://fallback.example/v1",
            fallbackModel = "fallback",
        )

        assertEquals(snapshotRoute, recovered)
    }
}

package com.labteto.dshmobile.local

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalHarnessSessionApprovalTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacySessionDefaultsToPerOperationApproval() {
        val session = json.decodeFromString(
            LocalHarnessSession.serializer(),
            """{"id":"legacy","title":"旧会话","planMode":false}""",
        )

        assertFalse(session.autoApproveMutations)
    }

    @Test
    fun autoApprovalRoundTripsWithSession() {
        val encoded = json.encodeToString(
            LocalHarnessSession.serializer(),
            LocalHarnessSession(id = "auto", autoApproveMutations = true),
        )
        val restored = json.decodeFromString(LocalHarnessSession.serializer(), encoded)

        assertTrue(restored.autoApproveMutations)
    }
}

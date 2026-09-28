package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelProfilesTest {
    @Test fun sameModelOnDifferentServicesHasDifferentCredentials() {
        val first = modelProfileId("shared-model", "https://service-a.example/v1")
        val second = modelProfileId("shared-model", "https://service-b.example/v1")
        assertNotEquals(first, second)
        assertEquals(first, modelProfileId(" shared-model ", "https://service-a.example/v1/"))
    }

    @Test fun currentProviderPresetsExposeRoutesAndDocumentedCapabilities() {
        val miniMax = LocalModelPresets.find("MiniMax-M3", "https://api.minimax.io/v1")!!
        assertTrue(LocalModelCapability.IMAGE in miniMax.capabilities)
        assertTrue(LocalModelCapability.VIDEO in miniMax.capabilities)
        assertEquals(true, miniMax.imageInputSupported)
        assertEquals("https://api.minimax.io/v1/chat/completions", miniMax.chatEndpoint)

        val kimi = LocalModelPresets.find("k3", "https://api.kimi.com/coding/v1")!!
        assertTrue(LocalModelCapability.IMAGE in kimi.capabilities)
        assertTrue(LocalModelCapability.VIDEO in kimi.capabilities)
        assertEquals(true, kimi.imageInputSupported)

        val kimi256k = LocalModelPresets.find("k3-256k", "https://api.kimi.com/coding/v1")!!
        assertTrue(LocalModelCapability.IMAGE in kimi256k.capabilities)
        assertFalse(LocalModelCapability.VIDEO in kimi256k.capabilities)
        assertEquals(true, kimi256k.imageInputSupported)

        val currentKimiIds = LocalModelPresets.entries
            .filter { it.provider.startsWith("Kimi Code") }
            .map { it.model }
            .toSet()
        assertEquals(
            setOf("k3", "k3-256k", "kimi-for-coding", "kimi-for-coding-highspeed"),
            currentKimiIds,
        )

        val glm = LocalModelPresets.find("glm-5-turbo", "https://open.bigmodel.cn/api/paas/v4")!!
        assertTrue(LocalModelCapability.TEXT in glm.capabilities)
        assertFalse(LocalModelCapability.IMAGE in glm.capabilities)
        assertEquals(false, glm.imageInputSupported)
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            glm.modelsEndpoint,
        )
    }

    @Test fun everyPresetHasAUniqueValidRoute() {
        val routes = LocalModelPresets.entries.map { modelProfileId(it.model, it.baseUrl) }
        assertEquals(routes.size, routes.toSet().size)
    }
}

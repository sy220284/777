package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalModelProfilesTest {
    @Test fun sameModelOnDifferentServicesHasDifferentCredentials() {
        val first = modelProfileId("shared-model", "https://service-a.example/v1")
        val second = modelProfileId("shared-model", "https://service-b.example/v1")
        assertNotEquals(first, second)
        assertEquals(first, modelProfileId(" shared-model ", "https://service-a.example/v1/"))
    }

    @Test fun everyPresetHasAUniqueValidRoute() {
        val routes = LocalModelPresets.entries.map { modelProfileId(it.model, it.baseUrl) }
        assertEquals(routes.size, routes.toSet().size)
    }
}

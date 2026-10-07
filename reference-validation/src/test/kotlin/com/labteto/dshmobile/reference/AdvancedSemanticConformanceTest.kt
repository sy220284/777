package com.labteto.dshmobile.reference

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedSemanticConformanceTest {
    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    @Test
    fun sessionProjectionRegistryMatchesPinnedOfficialSemantics() {
        val expected = json.decodeFromString(
            ProjectionRegistrySemanticFixture.serializer(),
            resource("official-semantic/session-projection-registry.json"),
        )
        val actual = AdvancedSemanticConformanceRunner().sessionProjectionRegistry()

        assertEquals("官方 Session Projection 高级语义不一致", expected, actual)
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)) {
            "缺少高级差分资源：$path"
        }.readText()
}

package com.labteto.dshmobile.reference

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceConformanceTest {
    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    @Test
    fun nativeCoreMatchesPinnedOfficialGoldenCases() = runTest {
        for (caseName in CASES) {
            val vector = readVector(caseName)
            val expected = readExpected(caseName)
            val actual = NativeConformanceRunner().run(vector)
            assertEquals("官方差分案例不一致：$caseName", expected, actual)
        }
    }

    private fun readVector(caseName: String): ConformanceVector =
        json.decodeFromString(
            ConformanceVector.serializer(),
            resource("vectors/$caseName.json"),
        )

    private fun readExpected(caseName: String): List<CanonicalEvent> =
        json.decodeFromString(
            ListSerializer(CanonicalEvent.serializer()),
            resource("official/$caseName.json"),
        )

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)) { "缺少差分资源：$path" }
            .readText()

    private companion object {
        val CASES = listOf(
            "plain-text",
            "single-tool",
            "unicode-text",
            "empty-text",
            "tool-with-text",
            "nested-arguments",
            "empty-tool-output",
            "two-tools",
            "three-tools",
            "same-tool-twice",
            "two-tool-steps",
            "three-tool-steps",
            "text-on-each-tool-step",
            "mixed-json-arguments",
            "unicode-tool-arguments",
            "numeric-arguments",
            "empty-arguments",
            "final-unicode",
            "four-tools",
            "five-tool-steps",
            "newline-text",
            "emoji-text",
        )
    }
}

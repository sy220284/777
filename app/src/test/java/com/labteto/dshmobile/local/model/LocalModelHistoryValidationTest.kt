package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelHistoryValidationTest {
    @Test
    fun acceptsParallelToolResultsInEitherOrderAndRepeatedIdsInLaterTurns() {
        val history = listOf(calls, result("b"), result("a"), calls, result("a"), result("b"), answer)
        validateCanonicalModelHistory(history.map(LocalCanonicalModelCodec::message))
    }

    @Test
    fun rejectsOrphanDuplicateMissingAndInterleavedResultsBeforeSending() {
        listOf(
            listOf(result("a")),
            listOf(calls, result("a"), result("a")),
            listOf(calls, result("a")),
            listOf(calls, answer, result("a"), result("b")),
            listOf(calls, result("unknown"), result("a"), result("b")),
        ).forEach { history ->
            val error = assertThrows(LocalModelException::class.java) {
                validateCanonicalModelHistory(history.map(LocalCanonicalModelCodec::message))
            }
            assertEquals("MODEL_HISTORY_INVALID", error.code)
            assertFalse(error.retryable)
        }
    }

    @Test
    fun rejectsEmptyWhitespaceAndReasoningOnlyAssistants() {
        listOf(
            """{"role":"assistant"}""",
            """{"role":"assistant","content":"   "}""",
            """{"role":"assistant","reasoning_content":"thinking"}""",
        ).forEach { raw ->
            assertThrows(LocalModelException::class.java) {
                validateCanonicalModelHistory(listOf(LocalCanonicalModelCodec.message(json(raw))))
            }
        }
    }

    private fun result(id: String) = json("""{"role":"tool","tool_call_id":"$id","content":"done"}""")
    private val answer = json("""{"role":"assistant","content":"done"}""")
    private val calls = json("""{"role":"assistant","tool_calls":[{"id":"a","function":{"name":"read","arguments":"{}"}},{"id":"b","function":{"name":"read","arguments":"{}"}}]}""")
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}

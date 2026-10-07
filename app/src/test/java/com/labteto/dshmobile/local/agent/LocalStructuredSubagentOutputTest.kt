package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStructuredSubagentOutputTest {
    private val schema = Json.parseToJsonElement(
        """{
          "type":"object",
          "properties":{
            "conclusion":{"type":"string","minLength":1},
            "verified":{"type":"boolean"}
          },
          "required":["conclusion","verified"],
          "additionalProperties":false
        }"""
    ).jsonObject

    @Test
    fun parsesAndCanonicalizesValidStructuredOutput() {
        val result = validateLocalStructuredSubagentOutput(
            """ { "verified": true, "conclusion": "通过" } """,
            schema,
        ).getOrThrow()

        assertEquals("通过", result.value["conclusion"]?.toString()?.trim('"'))
        assertEquals(true, result.value["verified"]?.toString()?.toBoolean())
        assertEquals(result.value.toString(), result.canonicalJson)
        assertNotEquals(result.schemaDigest, result.resultDigest)
    }

    @Test
    fun canonicalOutputAndDigestIgnoreObjectKeyOrder() {
        val nestedSchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "a":{"type":"integer"},
                "nested":{
                  "type":"object",
                  "properties":{
                    "x":{"type":"string"},
                    "y":{"type":"string"}
                  },
                  "required":["x","y"],
                  "additionalProperties":false
                }
              },
              "required":["a","nested"],
              "additionalProperties":false
            }"""
        ).jsonObject

        val first = validateLocalStructuredSubagentOutput(
            """{"nested":{"y":"2","x":"1"},"a":1}""",
            nestedSchema,
        ).getOrThrow()
        val second = validateLocalStructuredSubagentOutput(
            """{"a":1,"nested":{"x":"1","y":"2"}}""",
            nestedSchema,
        ).getOrThrow()

        assertEquals(first.canonicalJson, second.canonicalJson)
        assertEquals(first.resultDigest, second.resultDigest)
        assertEquals(first.schemaDigest, second.schemaDigest)
        assertEquals(
            """{"a":1,"nested":{"x":"1","y":"2"}}""",
            first.canonicalJson,
        )
    }

    @Test
    fun rejectsMarkdownFenceAndExtraText() {
        listOf(
            """```json
{"conclusion":"通过","verified":true}
```""",
            """结果如下：{"conclusion":"通过","verified":true}""",
        ).forEach { raw ->
            val error = validateLocalStructuredSubagentOutput(raw, schema).exceptionOrNull()
                as LocalStructuredSubagentOutputException

            assertEquals("SUBAGENT_STRUCTURED_OUTPUT_INVALID_JSON", error.failure.code)
        }
    }

    @Test
    fun rejectsSchemaMismatchWithoutRepairingOrRetrying() {
        val error = validateLocalStructuredSubagentOutput(
            """{"conclusion":"通过","verified":true,"extra":"x"}""",
            schema,
        ).exceptionOrNull() as LocalStructuredSubagentOutputException

        assertEquals("SUBAGENT_STRUCTURED_OUTPUT_SCHEMA_MISMATCH", error.failure.code)
        assertTrue(error.failure.detail.contains("未声明字段"))
    }

    @Test
    fun structuredInstructionIncludesExactSchemaAndForbidsExtraText() {
        val instruction = localStructuredSubagentInstruction(schema)

        assertTrue(instruction.contains(schema.toString()))
        assertTrue(instruction.contains("只输出一个 JSON 对象"))
        assertTrue(instruction.contains("不要使用 Markdown 代码块"))
    }

    @Test
    fun capabilityValidationAcceptsValidSchemaOnlyWhenStructuredOutputEnabled() {
        val spec = LocalSubagentLaunchSpec(
            task = "返回审计结论",
            modelOverride = null,
            maxSteps = 20,
            capabilities = LocalSubagentCapabilities(outputSchema = schema),
        )

        assertEquals(
            spec,
            validateLocalSubagentLaunchSpec(
                spec,
                structuredOutputSupported = true,
            ),
        )
    }

    @Test
    fun capabilityValidationRejectsInvalidOutputSchemaBeforeAgentStarts() {
        val invalidSchema = buildJsonObject {
            put("type", "object")
            put("required", Json.parseToJsonElement("""["missing"]"""))
            put("properties", buildJsonObject {})
        }
        val spec = LocalSubagentLaunchSpec(
            task = "返回结构化结果",
            modelOverride = null,
            maxSteps = 20,
            capabilities = LocalSubagentCapabilities(outputSchema = invalidSchema),
        )

        val error = runCatching {
            validateLocalSubagentLaunchSpec(
                spec,
                structuredOutputSupported = true,
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("SUBAGENT_OUTPUT_SCHEMA_INVALID"))
    }
}

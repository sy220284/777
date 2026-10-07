package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentCapabilitiesTest {
    private fun resultSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("conclusion", buildJsonObject { put("type", "string") })
            put("verified", buildJsonObject { put("type", "boolean") })
            put("files", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("conclusion"))
            add(JsonPrimitive("verified"))
        })
        put("additionalProperties", false)
    }

    @Test
    fun acceptsValidStructuredResult() {
        val parsed = parseStructuredSubagentResult(
            """{"conclusion":"已核验","verified":true,"files":["a.kt"]}""",
            resultSchema(),
        )

        assertTrue(parsed.valid)
        assertTrue(parsed.issues.isEmpty())
        assertEquals("已核验", parsed.value!!.jsonObject["conclusion"]!!.jsonPrimitive.content)
    }

    @Test
    fun rejectsMissingRequiredField() {
        val parsed = parseStructuredSubagentResult(
            """{"conclusion":"完成"}""",
            resultSchema(),
        )

        assertFalse(parsed.valid)
        assertTrue(parsed.issues.any { it.contains("verified") && it.contains("必填") })
    }

    @Test
    fun rejectsAdditionalFieldWhenSchemaDisallowsIt() {
        val parsed = parseStructuredSubagentResult(
            """{"conclusion":"完成","verified":true,"extra":1}""",
            resultSchema(),
        )

        assertFalse(parsed.valid)
        assertTrue(parsed.issues.any { it.contains("额外字段") && it.contains("extra") })
    }

    @Test
    fun rejectsUnsupportedSchemaKeywordInsteadOfIgnoringIt() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("pattern", "^[a-z]+$")
                })
            })
        }

        val issues = validateStructuredOutputSchema(schema)

        assertTrue(issues.any { it.contains("不支持的 Schema 关键字") && it.contains("pattern") })
    }

    @Test
    fun persistentMutableAgentIsRejectedBeforeExecution() {
        val issues = LocalSubagentCapabilities(
            allowMutation = true,
            continuable = true,
        ).validateLaunch(backgroundJobId = "job-1")

        assertTrue(issues.any { it.contains("不允许可变执行") })
    }

    @Test
    fun continuableAgentRequiresPersistentJobIdentity() {
        val issues = LocalSubagentCapabilities(
            continuable = true,
        ).validateLaunch(backgroundJobId = null)

        assertTrue(issues.any { it.contains("必须绑定持久后台 Job") })
    }

    @Test
    fun invalidJsonCannotBeReportedAsStructuredSuccess() {
        val parsed = parseStructuredSubagentResult(
            "not-json",
            resultSchema(),
        )

        assertFalse(parsed.valid)
        assertEquals(listOf("子代理输出不是合法 JSON"), parsed.issues)
    }
}

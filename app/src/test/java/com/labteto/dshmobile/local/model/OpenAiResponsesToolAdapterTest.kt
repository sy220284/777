package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesToolAdapterTest {
    private fun tool(
        strict: Boolean? = null,
        parameters: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
    ) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "read")
            put("parameters", parameters)
            strict?.let { put("strict", it) }
        })
    }

    @Test
    fun planSharingNamespaceAndFunctionAlwaysHaveRequiredFields() {
        val adapted = OpenAiResponsesToolAdapter.adapt(
            tools = buildJsonArray { add(tool()) },
            planSharing = true,
            enforceOpenAiToolSchema = true,
        )

        val namespace = adapted.single().jsonObject
        assertEquals("namespace", namespace["type"]!!.jsonPrimitive.content)
        assertTrue(namespace["description"]!!.jsonPrimitive.content.isNotBlank())
        val function = namespace["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", function["type"]!!.jsonPrimitive.content)
        assertEquals("read", function["name"]!!.jsonPrimitive.content)
        assertTrue(function["description"]!!.jsonPrimitive.content.isNotBlank())
        assertNotNull(function["parameters"])
        assertFalse(function["strict"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun officialOpenAiStrictSchemaRejectsMissingAdditionalPropertiesFalse() {
        val parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
        }

        val error = runCatching {
            OpenAiResponsesToolAdapter.adapt(
                tools = buildJsonArray { add(tool(strict = true, parameters = parameters)) },
                planSharing = false,
                enforceOpenAiToolSchema = true,
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
    }

    @Test
    fun thirdPartyResponsesDoesNotInheritOpenAiStrictDialectRules() {
        val parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
        }

        val adapted = OpenAiResponsesToolAdapter.adapt(
            tools = buildJsonArray { add(tool(strict = true, parameters = parameters)) },
            planSharing = false,
            enforceOpenAiToolSchema = false,
        )
        assertEquals(1, adapted.size)
        assertTrue(adapted.single().jsonObject["strict"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun malformedParametersAreRejectedBeforeNetwork() {
        val invalid = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "read")
                put("parameters", JsonArray(emptyList()))
            })
        }

        val error = runCatching {
            OpenAiResponsesToolAdapter.adapt(
                tools = buildJsonArray { add(invalid) },
                planSharing = false,
                enforceOpenAiToolSchema = true,
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
    }
}

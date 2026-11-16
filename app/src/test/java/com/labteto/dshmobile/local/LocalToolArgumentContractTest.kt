package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.toolArgumentContractSummary
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolArgumentContractTest {
    @Test
    fun schemaRecoveryDescribesRequiredFieldsBeyondTheOldTwelveFieldLimit() {
        val schema = buildJsonObject {
            put("function", buildJsonObject {
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("z_required")) })
                    put("properties", buildJsonObject {
                        repeat(15) { index ->
                            put("optional_$index", buildJsonObject { put("type", "number") })
                        }
                        put("z_required", buildJsonObject { put("type", "string") })
                    })
                })
            })
        }
        val summary = toolArgumentContractSummary(schema)
        assertTrue(summary.contains("z_required:string"))
        assertTrue(summary.contains("optional_14:number"))
        assertTrue(summary.contains("必填=z_required"))
    }

    @Test
    fun correctsRealQuestionOptionsAndSubagentSchemaArguments() {
        val schemas = LocalToolCatalog.specs.map { it.jsonObject }
        fun contract(name: String): String {
            val schema = schemas.first {
                it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == name
            }
            return toolArgumentContractSummary(schema)
        }
        assertTrue(contract("ask_user_question").contains("options:array"))
        assertTrue(contract("subagent").contains("output_schema:object"))
        assertTrue(contract("subagent").contains("required 必须是字符串数组"))
    }
}

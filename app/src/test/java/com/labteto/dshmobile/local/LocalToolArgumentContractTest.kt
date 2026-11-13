package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.toolArgumentContractSummary
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolArgumentContractTest {
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

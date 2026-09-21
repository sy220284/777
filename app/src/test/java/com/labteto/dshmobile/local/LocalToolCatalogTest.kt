package com.labteto.dshmobile.local

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCatalogTest {
    @Test
    fun exposesAndroidAdaptableOfficialCapabilityFamilies() {
        val names = LocalToolCatalog.specs.mapNotNull {
            it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        }.toSet()

        assertTrue(
            names.containsAll(
                setOf(
                    "read", "write", "edit", "glob", "grep", "bash", "job_list",
                    "web_search", "web_fetch", "json_query", "network_diagnose", "environment_info",
                    "skill", "todo_write", "create_goal",
                    "ask_user_question", "subagent", "subagent_fork", "workflow",
                    "session_search", "session_event_search", "session_trace",
                    "session_event_trace", "session_event_read", "present",
                ),
            ),
        )
    }

    @Test
    fun workflowAndSubagentExposeAdvancedRoutingOptions() {
        val functions = LocalToolCatalog.specs.associateBy {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        val workflowProperties = functions.getValue("workflow").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        val modes = workflowProperties["mode"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        val subagentProperties = functions.getValue("subagent").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject

        assertEquals(setOf("parallel", "pipeline"), modes.toSet())
        assertTrue("model" in subagentProperties)
        assertTrue("max_steps" in subagentProperties)
    } 
    @Test
    fun webFetchExposesBoundedLargeResponseControls() {
        val functions = LocalToolCatalog.specs.associateBy {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        val properties = functions.getValue("web_fetch").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject

        assertTrue("max_bytes" in properties)
        assertTrue("format" in properties)
        assertTrue("run_in_background" in properties)
    }

}

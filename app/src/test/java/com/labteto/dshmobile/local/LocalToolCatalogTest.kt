package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.tools.LocalToolCatalog
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
                    "read", "file_inspect", "write", "edit", "apply_patch", "glob", "grep", "bash", "job_list",
                    "web_search", "web_fetch", "http_request", "download_file", "json_query",
                    "network_diagnose", "environment_info", "capability_search",
                    "skill", "todo_write", "create_goal",
                    "ask_user_question", "subagent", "subagent_fork", "workflow",
                    "session_search", "session_event_search", "session_trace",
                    "session_event_trace", "session_event_read", "memory_search", "memory_list", "memory_remember",
                    "memory_update", "memory_forget", "present",
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
        val forkProperties = functions.getValue("subagent_fork").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject

        assertEquals(setOf("parallel", "pipeline"), modes.toSet())
        assertTrue("model" in subagentProperties)
        assertTrue("max_steps" in subagentProperties)
        assertTrue("output_schema" in subagentProperties)
        assertTrue("allowed_tools" in subagentProperties)
        assertTrue("output_schema" in forkProperties)
        assertTrue("allowed_tools" in forkProperties)
        assertTrue("output_schema" in workflowProperties)
        assertTrue("allowed_tools" in workflowProperties)
    } 

    @Test
    fun teamSpawnExposesExplicitFreshOrForkContext() {
        val functions = LocalToolCatalog.specs.associateBy {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        val properties = functions.getValue("team_spawn").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        val contexts = properties.getValue("context").jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(setOf("fresh", "fork"), contexts.toSet())
    }

    @Test
    fun teamCatalogExposesLifecycleMessagesAndStopAll() {
        val names = LocalToolCatalog.specs.mapNotNull {
            it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        }.toSet()

        assertTrue(
            names.containsAll(
                setOf(
                    "team_members",
                    "team_member_status",
                    "team_create_member",
                    "team_start_member",
                    "team_spawn",
                    "team_send_message",
                    "team_messages",
                    "team_wait_for_message",
                    "team_disable_member",
                    "team_dismiss_member",
                    "team_stop_all",
                    "team_wait",
                ),
            ),
        )
    }

    @Test
    fun teamResultToolsExposeDurableSequenceCursor() {
        val functions = LocalToolCatalog.specs.associateBy {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        val messages = functions.getValue("team_messages").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        val wait = functions.getValue("team_wait_for_message").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject

        assertTrue("after_sequence" in messages)
        assertTrue("after_sequence" in wait)
    }

    @Test
    fun sessionEventSearchExposesPagingControls() {
        val functions = LocalToolCatalog.specs.associateBy {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        val properties = functions.getValue("session_event_search").jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject

        assertTrue("limit" in properties)
        assertTrue("after_sequence" in properties)
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

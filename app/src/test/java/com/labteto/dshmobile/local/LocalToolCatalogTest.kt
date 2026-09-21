package com.labteto.dshmobile.local

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                    "web_search", "web_fetch", "skill", "todo_write", "create_goal",
                    "ask_user_question", "subagent", "subagent_fork", "workflow",
                    "session_search", "session_event_search", "session_trace",
                    "session_event_trace", "session_event_read", "present",
                ),
            ),
        )
    }
}

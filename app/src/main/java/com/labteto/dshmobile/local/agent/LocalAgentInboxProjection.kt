package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Canonical typed Inbox -> model history projection for Lead, live child and cold recovery.
 * Agent-originated messages retain their untrusted-source wrapper from durable admission.
 */
internal fun modelMessageForAgentInput(input: QueuedAgentInput): JsonObject =
    input.modelMessage ?: buildJsonObject {
        put("role", "user")
        put("content", input.content)
    }

package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared safety gate for post-admission continuation.
 *
 * A continuation is a new request built on durable history; it never replays the failed provider
 * request. Callers may add stricter conditions (for example Work requires an empty user-input queue).
 */
internal data class LocalAgentContinuationPolicy(
    val maxContinuations: Int = 2,
    val prompt: String,
) {
    init {
        require(maxContinuations >= 0) { "maxContinuations must be non-negative" }
        require(prompt.isNotBlank()) { "continuation prompt must not be blank" }
    }

    fun allows(error: LocalModelException?, completedContinuations: Int): Boolean =
        error?.continuationEligible == true && completedContinuations < maxContinuations

    fun message(error: LocalModelException, completedContinuations: Int): JsonObject? {
        if (!allows(error, completedContinuations)) return null
        return buildJsonObject {
            put("role", "user")
            put("content", prompt)
        }
    }
}

internal val DEFAULT_AGENT_CONTINUATION_POLICY = LocalAgentContinuationPolicy(
    maxContinuations = 2,
    prompt = "继续未完成任务。上一模型请求中断且不能安全重放；基于已持久化上下文和工具结果继续，禁止重复已经完成并有结果的副作用操作。",
)

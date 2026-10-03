package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Bounded task-intent projection used only to decide which optional tool schemas to pre-attach. */
internal data class LocalToolCapabilityIntent(
    val context: String,
    val requestsGitHub: Boolean,
) {
    companion object {
        fun from(
            input: String,
            history: List<JsonObject>,
        ): LocalToolCapabilityIntent {
            val context = buildString {
                append(input.takeLast(4_000))
                history.asReversed().asSequence()
                    .filter { message -> message["role"]?.jsonPrimitive?.contentOrNull == "user" }
                    .mapNotNull { message -> (message["content"] as? JsonPrimitive)?.contentOrNull }
                    .filterNot(::isSyntheticCheckpoint)
                    .take(4)
                    .forEach { content ->
                        append('\n')
                        append(content.takeLast(1_500))
                    }
            }.takeLast(10_000)
            val normalized = context.lowercase()
            return LocalToolCapabilityIntent(
                context = context,
                requestsGitHub = GITHUB_INTENT_MARKERS.any(normalized::contains) ||
                    PR_INTENT_REGEX.containsMatchIn(normalized),
            )
        }

        private fun isSyntheticCheckpoint(content: String): Boolean =
            "<work-checkpoint>" in content ||
                "<compacted-summary>" in content ||
                "<chat-continuity>" in content

        private val GITHUB_INTENT_MARKERS = listOf(
            "github",
            "pull request",
            "合并请求",
            "远程仓库",
            "repository",
            "issue",
            "github actions",
            "release",
        )
        private val PR_INTENT_REGEX = Regex("(^|[^a-z])pr([^a-z]|$)")
    }
}

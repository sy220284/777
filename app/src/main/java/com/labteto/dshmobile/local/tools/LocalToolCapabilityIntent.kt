package com.labteto.dshmobile.local.tools

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
            val recentUserInputs = history.asReversed().asSequence()
                .filter { message -> message["role"]?.jsonPrimitive?.contentOrNull == "user" }
                .mapNotNull { message -> (message["content"] as? JsonPrimitive)?.contentOrNull }
                .filterNot(::isSyntheticCheckpoint)
                .take(4)
                .toList()
            val context = buildString {
                append(input.takeLast(4_000))
                recentUserInputs.forEach { content ->
                    append('\n')
                    append(content.takeLast(1_500))
                }
            }.takeLast(10_000)
            val githubIntent = sequenceOf(input)
                .plus(recentUserInputs.asSequence())
                .mapNotNull(::githubIntentSignal)
                .firstOrNull()
                ?: false
            return LocalToolCapabilityIntent(
                context = context,
                requestsGitHub = githubIntent,
            )
        }

        private fun isSyntheticCheckpoint(content: String): Boolean =
            "<work-checkpoint>" in content ||
                "<compacted-summary>" in content ||
                "<chat-continuity>" in content

        private fun githubIntentSignal(text: String): Boolean? {
            val normalized = text.lowercase()
            // A natural-language negation may contain a space before the provider name.
            val negationText = normalized.replace(Regex("\\s+"), "")
            if (GITHUB_NEGATION_MARKERS.any(negationText::contains)) return false
            if (
                GITHUB_INTENT_MARKERS.any(normalized::contains) ||
                PR_INTENT_REGEX.containsMatchIn(normalized)
            ) {
                return true
            }
            return null
        }

        private val GITHUB_NEGATION_MARKERS = listOf(
            "不用github",
            "不使用github",
            "不要github",
            "不涉及github",
            "不涉及远程仓库",
            "无需远程仓库",
        )
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

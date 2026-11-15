package com.labteto.dshmobile.local.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Bounded task-intent projection used only to decide which optional tool schemas to pre-attach. */
internal data class LocalToolCapabilityIntent(
    val requestsGitHub: Boolean,
) {
    companion object {
        fun from(
            input: String,
            history: List<JsonObject>,
        ): LocalToolCapabilityIntent {
            // The latest real request decides which provider is needed. Historical
            // GitHub mentions are relevant only for an explicit "continue" command.
            // Do not resurrect credentials and tool schemas from unrelated old tasks.
            val current = githubIntentSignal(input)
            val recent = if (current == null &&
                input.trim().lowercase() in CONTINUATION_COMMANDS
            ) {
                meaningfulUserInputs(history).take(4)
                    .mapNotNull(::githubIntentSignal).firstOrNull()
            } else null
            return LocalToolCapabilityIntent(
                requestsGitHub = current ?: recent ?: false,
            )
        }

        // Only real user messages may trigger newly registered tools after a resumed run.
        fun latestUserInput(history: List<JsonObject>): String? =
            meaningfulUserInputs(history).firstOrNull()

        private fun meaningfulUserInputs(history: List<JsonObject>): Sequence<String> =
            history.asReversed().asSequence()
                .filter { message -> message["role"]?.jsonPrimitive?.contentOrNull == "user" }
                .mapNotNull { message -> (message["content"] as? JsonPrimitive)?.contentOrNull }
                .filterNot(::isSyntheticCheckpoint)

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

        private val CONTINUATION_COMMANDS = setOf(
            "继续", "继续吧", "继续执行", "继续处理", "接着", "接着做", "接着来", "接着处理",
        )
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

/** A local archive reference on the newest user turn admits archive tools without making them global core tools. */
internal fun latestUserTurnHasArchive(history: List<JsonObject>): Boolean {
    val user = history.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        ?: return false
    val parts = user["content"] as? JsonArray ?: return false
    return parts.any { part ->
        val obj = part as? JsonObject ?: return@any false
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "local_file_ref") return@any false
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val type = obj["mediaType"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        listOf(".zip", ".tar", ".tar.gz", ".tgz", ".gz", ".jar", ".aar", ".apk",
            ".docx", ".xlsx", ".pptx", ".epub").any(name::endsWith) ||
            type in setOf("application/zip", "application/gzip", "application/x-gzip",
                "application/x-tar")
    }
}

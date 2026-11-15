package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
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
                isContinuationTaskRequest(input)
            ) {
                // Continue the latest substantive user task. A non-GitHub request
                // must stop older GitHub mentions from resurrecting tool schemas.
                meaningfulUserInputs(history)
                    .firstOrNull { !isContinuationTaskRequest(it) }
                    ?.let(::githubIntentSignal)
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
                // History compaction marks its own messages with a durable provenance
                // field. Literal tags in a genuine user request are ordinary text.
                .filterNot { message -> isTrustedContextCheckpointModelMessage(message) }
                .mapNotNull { message -> userIntentText(message["content"]) }

        /**
         * Multimodal user turns carry text as ordered blocks, not one JsonPrimitive.
         * Reading only string content would resurrect a previous task's tools for a
         * perfectly valid text+attachment request. Media-only input is still a new turn.
         */
        private fun userIntentText(content: JsonElement?): String? = when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { node ->
                val part = node as? JsonObject ?: return@mapNotNull null
                val type = (part["type"] as? JsonPrimitive)?.contentOrNull
                if (type !in setOf("text", "input_text")) return@mapNotNull null
                (part["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            }.joinToString("\n").ifBlank { "本轮用户包含非文本附件" }
            else -> null
        }

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

        /** The Work tool focus and provider selection share one task-boundary rule.
         * A neutral follow-up continues the current task; an instruction such as
         * "继续修改本地页面" names distinct work and starts a new task.
         */
        internal fun isContinuationTaskRequest(text: String): Boolean {
            val normalized = text.trim().lowercase()
                .replace(Regex("""[\s,，。！!；;:：]+"""), "")
            if (normalized in CONTINUATION_COMMANDS) return true
            val prefix = listOf("接下来继续", "继续", "接着")
                .firstOrNull { normalized.startsWith(it) } ?: return false
            val tail = normalized.removePrefix(prefix)
            return tail.matches(NEUTRAL_CONTINUATION_TAIL) ||
                tail.matches(PREVIOUS_PLAN_CONTINUATION_TAIL)
        }

        private val NEUTRAL_CONTINUATION_TAIL = Regex(
            """^(?:在本地)?(?:完成|做完|处理|推进|执行|进行)?(?:剩余|余下|未完成的)?(?:工作|任务|步骤|部分|问题)?$"""
        )
        private val PREVIOUS_PLAN_CONTINUATION_TAIL = Regex(
            """^(?:按|按照)(?:原|之前|此前|上面|上述)(?:的)?(?:要求|方案)(?:全部)?(?:完成|执行|处理|继续)?$"""
        )
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

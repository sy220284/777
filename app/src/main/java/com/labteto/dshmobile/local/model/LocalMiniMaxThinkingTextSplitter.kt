package com.labteto.dshmobile.local.model

/**
 * MiniMax OpenAI-compatible streams may occasionally embed thinking in the content channel
 * despite reasoning_split=true. Split only a leading <think> block. This is deliberately not
 * a general XML/HTML stripper: quoted tags, Markdown code and later text are ordinary content.
 *
 * A stream can split either marker across any number of deltas. Hold incomplete marker prefixes
 * until their status is unambiguous, so a raw "<thi" is never rendered to the user.
 * This affects visible text only; native reasoning_details and tool metadata remain untouched.
 */
internal class LocalMiniMaxThinkingTextSplitter {
    private enum class Stage { PREFIX, THINKING, BODY }
    private var stage = Stage.PREFIX
    private val pending = StringBuilder()

    fun accept(fragment: String): LocalModelDelta {
        if (fragment.isEmpty()) return LocalModelDelta()
        return when (stage) {
            Stage.BODY -> LocalModelDelta(content = fragment)
            Stage.THINKING -> acceptThinking(fragment)
            Stage.PREFIX -> {
                pending.append(fragment)
                val candidate = pending.toString().trimStart()
                when {
                    candidate.startsWith(OPEN, ignoreCase = true) -> {
                        stage = Stage.THINKING
                        pending.clear()
                        acceptThinking(candidate.substring(OPEN.length))
                    }
                    candidate.length < OPEN.length &&
                        OPEN.startsWith(candidate, ignoreCase = true) &&
                        pending.length <= MAX_PREFIX_CHARS -> LocalModelDelta()
                    else -> {
                        stage = Stage.BODY
                        val visible = pending.toString()
                        pending.clear()
                        LocalModelDelta(content = visible)
                    }
                }
            }
        }
    }

    private fun acceptThinking(fragment: String): LocalModelDelta {
        pending.append(fragment)
        val text = pending.toString()
        val end = text.indexOf(CLOSE, ignoreCase = true)
        if (end >= 0) {
            stage = Stage.BODY
            pending.clear()
            return LocalModelDelta(
                content = text.substring(end + CLOSE.length),
                reasoning = text.substring(0, end),
            )
        }
        val held = (1 until CLOSE.length).lastOrNull {
            text.endsWith(CLOSE.substring(0, it), ignoreCase = true)
        } ?: 0
        val ready = text.dropLast(held)
        pending.clear()
        if (held > 0) pending.append(text.takeLast(held))
        return LocalModelDelta(reasoning = ready)
    }

    fun finish(): LocalModelDelta {
        val remaining = pending.toString()
        pending.clear()
        return when (stage) {
            Stage.THINKING -> LocalModelDelta(reasoning = remaining)
            Stage.PREFIX -> {
                val candidate = remaining.trimStart()
                // Never expose a partial opening thinking marker.
                if (candidate.isNotEmpty() && OPEN.startsWith(candidate, ignoreCase = true))
                    LocalModelDelta(reasoning = remaining)
                else LocalModelDelta(content = remaining)
            }
            Stage.BODY -> LocalModelDelta()
        }
    }

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"
        private const val MAX_PREFIX_CHARS = 256

        fun splitComplete(text: String): LocalModelDelta {
            val splitter = LocalMiniMaxThinkingTextSplitter()
            val first = splitter.accept(text)
            val last = splitter.finish()
            return LocalModelDelta(first.content + last.content, first.reasoning + last.reasoning)
        }
    }
}

/**
 * Presentation compatibility for older persisted assistant text which predates MiniMax splitting.
 * No mutation of the raw session event, tool record or provider replay payload occurs here.
 */
internal fun visibleAssistantText(text: String): String {
    // Fast path matters while Compose re-renders growing assistant streams: almost all
    // providers already return plain text, which needs no reparsing or copying.
    val first = text.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return text
    val head = text.substring(first, minOf(text.length, first + 7))
    if (!head.startsWith("<think>", ignoreCase = true) &&
        !"<think>".startsWith(head, ignoreCase = true)
    ) return text
    return LocalMiniMaxThinkingTextSplitter.splitComplete(text).content
}

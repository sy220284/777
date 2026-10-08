package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair

private val CHARACTER_THOUGHT_HEADER =
    Regex("^\\s*【心声[:：]([^】\\r\\n]{1,120})】\\s*")

/**
 * Render a deliberately authored fictional character aside, never a provider reasoning trace.
 * A half-streamed header stays hidden until the closing marker has arrived.
 */
internal fun extractLocalCharacterThought(text: String): Pair<String?, String> {
    val match = CHARACTER_THOUGHT_HEADER.find(text)
    if (match == null) {
        if (text.trimStart().startsWith("【心声")) return null to ""
        return null to text
    }
    val thought = truncateWithoutSplittingSurrogatePair(match.groupValues[1].trim(), 20)
        .takeIf(String::isNotBlank)
    return thought to text.substring(match.range.last + 1).trimStart()
}

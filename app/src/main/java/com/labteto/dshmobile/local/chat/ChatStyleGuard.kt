package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelReply

/**
 * Explicit expression-preference diagnostics and lossless media-aware text replacement.
 *
 * Preferences are supplied before generation. Diagnostics never delete completed speech.
 */
internal object ChatStyleGuard {
    // Only the user's explicit phrase choices participate in filtering.
    val bannedPhrases: List<String> = emptyList()

    fun activePhrases(
        customPhrases: List<String> = emptyList(),
        personaPhrases: List<String> = emptyList(),
        enabled: Boolean = true,
    ): List<String> {
        if (!enabled) return emptyList()
        return (bannedPhrases + customPhrases + personaPhrases)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

    fun violations(
        text: String,
        extraBannedPhrases: List<String> = emptyList(),
        builtInEnabled: Boolean = true,
    ): List<String> =
        ((if (builtInEnabled) bannedPhrases else emptyList()) + extraBannedPhrases)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .filter { phrase -> phrase in text }
            .toList()

    fun violations(text: String, phrases: Collection<String>): List<String> =
        phrases.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .filter { phrase -> phrase in text }
            .toList()

    fun withContent(reply: LocalModelReply, content: String): LocalModelReply =
        com.labteto.dshmobile.local.model.withLocalReplyContent(reply, content)
}

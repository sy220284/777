package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal data class LocalChatStyleGuardSettings(
    val enabled: Boolean,
    val customPhrases: List<String>,
)

/** Stable Chat-owned configuration port consumed by Settings and app bootstrap. */
internal interface LocalChatStyleGuardSettingsPort {
    val builtInPhrases: List<String>
    fun initialSettings(): LocalChatStyleGuardSettings
    fun configureEnabled(enabled: Boolean)
    fun addPhrase(value: String): Boolean
    fun removePhrase(value: String)
    fun clearHits()
    fun recordHits(violations: List<String>)
}

@Singleton
internal class LocalChatStyleGuardSettingsCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val state: LocalChatStatePort,
) : LocalChatStyleGuardSettingsPort {
    private val preferences = LocalHarnessPreferences.from(context)

    override val builtInPhrases: List<String> get() = ChatStyleGuard.bannedPhrases

    override fun initialSettings(): LocalChatStyleGuardSettings = LocalChatStyleGuardSettings(
        enabled = preferences.getBoolean(KEY_CHAT_STYLE_GUARD, true),
        customPhrases = loadCustomPhrases(),
    )

    override fun configureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CHAT_STYLE_GUARD, enabled).apply()
        state.update { current ->
            current.copy(chat = current.chat.copy(chatStyleGuardEnabled = enabled))
        }
    }

    override fun addPhrase(value: String): Boolean {
        val phrase = normalizePhrase(value) ?: return false
        val current = state.value.chat.chatStyleGuardCustomPhrases
        if (phrase in current || current.size >= MAX_CUSTOM_CHAT_FILTERS) return false
        val updated = current + phrase
        persistCustomPhrases(updated)
        state.update { it.copy(chat = it.chat.copy(chatStyleGuardCustomPhrases = updated)) }
        return true
    }

    override fun removePhrase(value: String) {
        val phrase = value.trim()
        if (phrase.isEmpty()) return
        val current = state.value.chat.chatStyleGuardCustomPhrases
        val updated = current.filterNot { it == phrase }
        if (updated == current) return
        persistCustomPhrases(updated)
        state.update { it.copy(chat = it.chat.copy(chatStyleGuardCustomPhrases = updated)) }
    }

    override fun clearHits() {
        state.update { it.copy(chat = it.chat.copy(styleGuardHits = emptyList())) }
    }

    override fun recordHits(violations: List<String>) {
        if (violations.isEmpty()) return
        state.update { current ->
            current.copy(
                chat = current.chat.copy(
                    styleGuardHits = (current.chat.styleGuardHits + violations)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                        .takeLast(MAX_STYLE_GUARD_HITS),
                ),
            )
        }
    }

    private fun loadCustomPhrases(): List<String> =
        preferences.getString(KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES, "")
            .orEmpty()
            .lineSequence()
            .mapNotNull(::normalizePhrase)
            .distinct()
            .take(MAX_CUSTOM_CHAT_FILTERS)
            .toList()

    private fun persistCustomPhrases(phrases: List<String>) {
        preferences.edit()
            .putString(KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES, phrases.joinToString("\n"))
            .apply()
    }

    private fun normalizePhrase(value: String): String? =
        value.replace('\n', ' ')
            .trim()
            .takeIf(String::isNotBlank)
            ?.take(MAX_CUSTOM_CHAT_FILTER_CHARS)

    private companion object {
        const val KEY_CHAT_STYLE_GUARD = "chat_style_guard_enabled"
        const val KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES = "chat_style_guard_custom_phrases"
        const val MAX_STYLE_GUARD_HITS = 20
        const val MAX_CUSTOM_CHAT_FILTERS = 50
        const val MAX_CUSTOM_CHAT_FILTER_CHARS = 32
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalChatStyleGuardSettingsModule {
    @Binds
    @Singleton
    abstract fun bindLocalChatStyleGuardSettingsPort(
        coordinator: LocalChatStyleGuardSettingsCoordinator,
    ): LocalChatStyleGuardSettingsPort
}

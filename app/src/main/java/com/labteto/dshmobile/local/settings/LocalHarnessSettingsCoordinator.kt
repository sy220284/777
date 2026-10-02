package com.labteto.dshmobile.local

import android.content.SharedPreferences
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.profile.UserProfile
import com.labteto.dshmobile.local.profile.UserProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class LocalHarnessSettingsCoordinator(
    private val preferences: SharedPreferences,
    private val userProfileStore: UserProfileStore,
    private val scope: CoroutineScope,
    private val state: () -> LocalHarnessState,
    private val updateState: ((LocalHarnessState) -> LocalHarnessState) -> Unit,
) {
    fun configureImageInputMode(mode: LocalImageInputMode) {
        preferences.edit().putString(KEY_IMAGE_INPUT_MODE, mode.name).apply()
        updateState { it.copy(imageInputMode = mode) }
    }

    fun configureRuntimeLimits(
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
        modelAttempts: Int,
    ) {
        val main = mainMaxSteps.coerceIn(4, 128)
        val subagent = subagentMaxSteps.coerceIn(1, 128)
        val attempts = modelAttempts.coerceIn(1, 5)
        preferences.edit()
            .putInt(KEY_MAIN_MAX_STEPS, main)
            .putInt(KEY_SUBAGENT_MAX_STEPS, subagent)
            .putInt(KEY_MODEL_ATTEMPTS, attempts)
            .apply()
        updateState {
            it.copy(
                mainMaxSteps = main,
                subagentMaxSteps = subagent,
                modelAttempts = attempts,
            )
        }
    }

    fun configureWorkerProfile(profileId: String?) {
        val normalized = profileId?.trim()?.takeIf(String::isNotBlank)
        val current = state()
        require(normalized == null || current.modelSelection.profiles.any { it.id == normalized }) {
            "子代理工作模型已不存在，请重新选择"
        }
        preferences.edit().apply {
            if (normalized == null) remove(KEY_WORKER_PROFILE_ID) else putString(KEY_WORKER_PROFILE_ID, normalized)
        }.apply()
        updateState {
            it.copy(modelSelection = it.modelSelection.copy(workerProfileId = normalized))
        }
    }

    fun configurePersonalization(
        customRules: String,
        autoRecall: Boolean,
        autoMemory: Boolean,
    ) {
        val profile = UserProfile(
            customRules = customRules.trim().take(6_000),
            autoRecall = autoRecall,
            autoMemory = autoMemory,
        )
        updateState {
            it.copy(
                userRules = profile.customRules,
                autoRecall = profile.autoRecall,
                autoMemory = profile.autoMemory,
            )
        }
        scope.launch { userProfileStore.write(profile) }
    }

    fun configureChatStyleGuard(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CHAT_STYLE_GUARD, enabled).apply()
        updateState { it.copy(chatStyleGuardEnabled = enabled) }
    }

    fun addChatStyleGuardPhrase(value: String): Boolean {
        val phrase = normalizeChatStyleGuardPhrase(value) ?: return false
        val current = state().chatStyleGuardCustomPhrases
        if (phrase in current || current.size >= MAX_CUSTOM_CHAT_FILTERS) return false
        val updated = current + phrase
        persistChatStyleGuardPhrases(updated)
        updateState { it.copy(chatStyleGuardCustomPhrases = updated) }
        return true
    }

    fun removeChatStyleGuardPhrase(value: String) {
        val phrase = value.trim()
        if (phrase.isEmpty()) return
        val current = state().chatStyleGuardCustomPhrases
        val updated = current.filterNot { it == phrase }
        if (updated == current) return
        persistChatStyleGuardPhrases(updated)
        updateState { it.copy(chatStyleGuardCustomPhrases = updated) }
    }

    fun clearChatStyleGuardHits() {
        updateState { it.copy(styleGuardHits = emptyList()) }
    }

    fun chatStreamFilterPhrases(
        snapshot: LocalHarnessState,
        persona: PersonaProfile = snapshot.chatPersona,
    ): List<String> = ChatStyleGuard.activePhrases(
        customPhrases = snapshot.chatStyleGuardCustomPhrases,
        personaPhrases = persona.bannedPhrases,
        enabled = snapshot.usageMode == LocalUsageMode.CHAT && snapshot.chatStyleGuardEnabled,
    )

    fun recordStyleGuardHits(violations: List<String>) {
        if (violations.isEmpty()) return
        updateState { current ->
            current.copy(
                styleGuardHits = (current.styleGuardHits + violations)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeLast(MAX_STYLE_GUARD_HITS),
            )
        }
    }

    private fun persistChatStyleGuardPhrases(phrases: List<String>) {
        preferences.edit()
            .putString(KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES, phrases.joinToString("\n"))
            .apply()
    }

    companion object {
        const val KEY_MAIN_MAX_STEPS = "main_max_steps"
        const val KEY_SUBAGENT_MAX_STEPS = "subagent_max_steps"
        const val KEY_MODEL_ATTEMPTS = "model_attempts"
        const val KEY_WORKER_PROFILE_ID = LOCAL_WORKER_PROFILE_ID_PREFERENCE
        const val KEY_IMAGE_INPUT_MODE = "image_input_mode"
        const val KEY_CHAT_STYLE_GUARD = "chat_style_guard_enabled"
        const val KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES = "chat_style_guard_custom_phrases"
        const val MAX_STYLE_GUARD_HITS = 20
        const val MAX_CUSTOM_CHAT_FILTERS = 50
        const val MAX_CUSTOM_CHAT_FILTER_CHARS = 32

        fun loadChatStyleGuardCustomPhrases(preferences: SharedPreferences): List<String> =
            preferences.getString(KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES, "")
                .orEmpty()
                .lineSequence()
                .mapNotNull(::normalizeChatStyleGuardPhrase)
                .distinct()
                .take(MAX_CUSTOM_CHAT_FILTERS)
                .toList()

        private fun normalizeChatStyleGuardPhrase(value: String): String? =
            value.replace('\n', ' ')
                .trim()
                .takeIf(String::isNotBlank)
                ?.take(MAX_CUSTOM_CHAT_FILTER_CHARS)
    }
}

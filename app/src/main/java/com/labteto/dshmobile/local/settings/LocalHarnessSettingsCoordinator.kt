package com.labteto.dshmobile.local.settings

import android.content.Context
import android.content.SharedPreferences
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.ChatStyleGuard
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.model.LOCAL_WORKER_PROFILE_ID_PREFERENCE
import com.labteto.dshmobile.local.profile.UserProfile
import com.labteto.dshmobile.local.profile.UserProfileStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
internal class LocalHarnessSettingsCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val userProfileStore: UserProfileStore,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state get() = runtimeStateStore.state.value
    private val personalizationGeneration = AtomicLong()
    private val personalizationWrites = Channel<Pair<Long, UserProfile>>(Channel.CONFLATED)

    init {
        scope.launch {
            // One writer preserves submission order; rapid edits retain only the latest pending
            // value, so an older IO coroutine cannot overwrite newer persisted preferences.
            for ((generation, profile) in personalizationWrites) {
                runCatching { userProfileStore.write(profile) }
                    .onFailure { error ->
                        updateState { current ->
                            if (personalizationGeneration.get() == generation) {
                                current.copy(error = error.message ?: "个性化设置保存失败")
                            } else current
                        }
                    }
            }
        }
    }

    internal fun readUserProfile(): UserProfile = userProfileStore.read()

    private fun updateState(transform: (LocalHarnessState) -> LocalHarnessState) {
        runtimeStateStore.mutableState.update(transform)
    }
    fun configureRuntimeLimits(
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
        modelAttempts: Int,
    ) {
        val main = LocalAgentRuntimeLimits.normalizeMainSteps(mainMaxSteps)
        val subagent = LocalAgentRuntimeLimits.normalizeSubagentSteps(subagentMaxSteps)
        val attempts = LocalAgentRuntimeLimits.normalizeModelAttempts(modelAttempts)
        preferences.edit()
            .putInt(KEY_MAIN_MAX_STEPS, main)
            .putInt(KEY_SUBAGENT_MAX_STEPS, subagent)
            .putInt(KEY_MODEL_ATTEMPTS, attempts)
            .apply()
        updateState {
            it.copy(
                mainMaxSteps = main,
                subagentMaxSteps = subagent,
                modelState = it.modelState.copy(modelAttempts = attempts),
            )
        }
    }

    fun configureWorkerProfile(profileId: String?) {
        val normalized = profileId?.trim()?.takeIf(String::isNotBlank)
        val current = state
        require(normalized == null || current.modelState.modelSelection.profiles.any { it.id == normalized }) {
            "子代理工作模型已不存在，请重新选择"
        }
        preferences.edit().apply {
            if (normalized == null) remove(KEY_WORKER_PROFILE_ID) else putString(KEY_WORKER_PROFILE_ID, normalized)
        }.apply()
        updateState {
            it.copy(modelState = it.modelState.copy(modelSelection = it.modelState.modelSelection.copy(workerProfileId = normalized)))
        }
    }

    @Synchronized
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
        val generation = personalizationGeneration.incrementAndGet()
        updateState {
            it.copy(
                userRules = profile.customRules,
                autoRecall = profile.autoRecall,
                autoMemory = profile.autoMemory,
            )
        }
        personalizationWrites.trySend(generation to profile).getOrThrow()
    }

    fun configureChatStyleGuard(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CHAT_STYLE_GUARD, enabled).apply()
        updateState { it.copy(chatStyleGuardEnabled = enabled) }
    }

    fun addChatStyleGuardPhrase(value: String): Boolean {
        val phrase = normalizeChatStyleGuardPhrase(value) ?: return false
        val current = state.chatStyleGuardCustomPhrases
        if (phrase in current || current.size >= MAX_CUSTOM_CHAT_FILTERS) return false
        val updated = current + phrase
        persistChatStyleGuardPhrases(updated)
        updateState { it.copy(chatStyleGuardCustomPhrases = updated) }
        return true
    }

    fun removeChatStyleGuardPhrase(value: String) {
        val phrase = value.trim()
        if (phrase.isEmpty()) return
        val current = state.chatStyleGuardCustomPhrases
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
        persona: PersonaProfile = snapshot.chat.chatPersona,
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

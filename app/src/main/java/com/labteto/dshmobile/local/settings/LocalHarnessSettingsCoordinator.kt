package com.labteto.dshmobile.local.settings

import android.content.Context
import android.content.SharedPreferences
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
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
import kotlinx.coroutines.launch

@Singleton
internal class LocalHarnessSettingsCoordinator internal constructor(
    private val preferences: SharedPreferences,
    private val userProfileStore: UserProfileStore,
    private val statePort: LocalSettingsStatePort,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    internal constructor(
        preferences: SharedPreferences,
        userProfileStore: UserProfileStore,
        runtimeStateStore: LocalRuntimeStateStore,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        preferences = preferences,
        userProfileStore = userProfileStore,
        statePort = localSettingsStatePort(runtimeStateStore),
        scope = scope,
    )

    @Inject
    constructor(
        @ApplicationContext context: Context,
        userProfileStore: UserProfileStore,
        runtimeStateStore: LocalRuntimeStateStore,
    ) : this(
        preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE),
        userProfileStore = userProfileStore,
        statePort = localSettingsStatePort(runtimeStateStore),
    )

    private val state get() = statePort.value
    private val personalizationGeneration = AtomicLong()
    private val personalizationWrites = Channel<Pair<Long, UserProfile>>(Channel.CONFLATED)

    init {
        scope.launch {
            // One writer preserves submission order; rapid edits retain only the latest pending
            // value, so an older IO coroutine cannot overwrite newer persisted preferences.
            for ((generation, profile) in personalizationWrites) {
                runCatching { userProfileStore.write(profile) }
                    .onFailure { error ->
                        if (personalizationGeneration.get() == generation) {
                            statePort.publishError(error.message ?: "个性化设置保存失败")
                        }
                    }
            }
        }
    }

    internal fun readUserProfile(): UserProfile = userProfileStore.read()

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
        statePort.updateRuntimeLimits(
            mainMaxSteps = main,
            subagentMaxSteps = subagent,
            modelAttempts = attempts,
        )
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
        statePort.updateWorkerProfile(normalized)
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
        statePort.updatePersonalization(
            userRules = profile.customRules,
            autoRecall = profile.autoRecall,
            autoMemory = profile.autoMemory,
        )
        personalizationWrites.trySend(generation to profile).getOrThrow()
    }

    companion object {
        const val KEY_MAIN_MAX_STEPS = "main_max_steps"
        const val KEY_SUBAGENT_MAX_STEPS = "subagent_max_steps"
        const val KEY_MODEL_ATTEMPTS = "model_attempts"
        const val KEY_WORKER_PROFILE_ID = LOCAL_WORKER_PROFILE_ID_PREFERENCE
    }
}

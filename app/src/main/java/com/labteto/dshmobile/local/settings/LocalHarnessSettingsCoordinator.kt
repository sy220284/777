package com.labteto.dshmobile.local.settings

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import android.content.SharedPreferences
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeSettings
import com.labteto.dshmobile.local.model.LocalModelExecutionSettings
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
        preferences = LocalHarnessPreferences.from(context),
        userProfileStore = userProfileStore,
        statePort = localSettingsStatePort(runtimeStateStore),
    )

    private val state get() = statePort.value
    private val personalizationGeneration = AtomicLong()
    private val personalizationWrites = Channel<Pair<Long, UserProfile>>(Channel.CONFLATED)

    init {
        scope.launch {
            var committedProfile = runCatching { userProfileStore.read() }.getOrDefault(UserProfile())
            // One writer preserves submission order; rapid edits retain only the latest pending
            // value. Runtime state is committed only after durable storage succeeds.
            for ((generation, profile) in personalizationWrites) {
                runCatching { userProfileStore.write(profile) }
                    .onSuccess {
                        committedProfile = profile
                        if (personalizationGeneration.get() == generation) {
                            statePort.updatePersonalization(
                                userRules = profile.customRules,
                                autoRecall = profile.autoRecall,
                                autoMemory = profile.autoMemory,
                            )
                        }
                    }
                    .onFailure { error ->
                        if (personalizationGeneration.get() == generation) {
                            statePort.updatePersonalization(
                                userRules = committedProfile.customRules,
                                autoRecall = committedProfile.autoRecall,
                                autoMemory = committedProfile.autoMemory,
                            )
                            statePort.publishError(error.message ?: "个性化设置保存失败")
                        }
                    }
            }
        }
    }

    internal fun readUserProfile(): UserProfile = userProfileStore.read()

    @Synchronized
    fun configureRuntimeLimits(
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
        modelAttempts: Int,
        workerProfileId: String? = state.modelState.modelSelection.workerProfileId,
    ) {
        val current = state
        // Validate both domains before staging anything. One editor publishes all
        // runtime-limit keys together, so an invalid worker identity cannot leave
        // only Agent limits updated while Model settings fail.
        val agent = LocalAgentRuntimeSettings.prepare(mainMaxSteps, subagentMaxSteps)
        val model = LocalModelExecutionSettings.prepare(
            modelAttempts = modelAttempts,
            workerProfileId = workerProfileId,
            availableProfiles = current.modelState.modelSelection.profiles,
        )
        // Android SharedPreferences may update its in-memory map even when commit()
        // reports disk failure. Capture all four original keys before staging.
        val keys = listOf(
            LocalAgentRuntimeSettings.KEY_MAIN_MAX_STEPS,
            LocalAgentRuntimeSettings.KEY_SUBAGENT_MAX_STEPS,
            com.labteto.dshmobile.local.model.LocalModelConfigContract.KEY_MODEL_ATTEMPTS,
            com.labteto.dshmobile.local.model.LocalModelConfigContract.KEY_WORKER_PROFILE_ID,
        )
        val previous = keys.associateWith { key ->
            when {
                !preferences.contains(key) -> null
                key == com.labteto.dshmobile.local.model.LocalModelConfigContract.KEY_WORKER_PROFILE_ID ->
                    preferences.getString(key, null)
                else -> preferences.getInt(key, 0)
            }
        }
        // Settings UI submits all four values as one decision and one disk commit.
        val committed = preferences.edit().also { editor ->
            LocalAgentRuntimeSettings.stage(editor, agent)
            LocalModelExecutionSettings.stage(editor, model)
        }.commit()
        if (!committed) {
            val failure = IllegalStateException("运行参数写入持久存储失败，状态保持原值")
            try {
                val rollback = preferences.edit()
                previous.forEach { (key, value) ->
                    when (value) {
                        is Int -> rollback.putInt(key, value)
                        is String -> rollback.putString(key, value)
                        null -> rollback.remove(key)
                    }
                }
                if (!rollback.commit()) {
                    failure.addSuppressed(IllegalStateException("运行参数内存回滚尚未持久完成"))
                }
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        statePort.updateRuntimeLimits(
            mainMaxSteps = agent.mainMaxSteps,
            subagentMaxSteps = agent.subagentMaxSteps,
            modelAttempts = model.modelAttempts,
            workerProfileId = model.workerProfileId,
        )
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
        personalizationWrites.trySend(generation to profile).getOrThrow()
    }
}

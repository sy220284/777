package com.labteto.dshmobile.local.settings

import com.labteto.dshmobile.local.persistence.LOCAL_HARNESS_PREFERENCES_NAME
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
        preferences = context.getSharedPreferences(LOCAL_HARNESS_PREFERENCES_NAME, Context.MODE_PRIVATE),
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

    fun configureRuntimeLimits(
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
        modelAttempts: Int,
    ) {
        val current = state
        val agent = LocalAgentRuntimeSettings.write(
            preferences = preferences,
            mainMaxSteps = mainMaxSteps,
            subagentMaxSteps = subagentMaxSteps,
        )
        val model = LocalModelExecutionSettings.write(
            preferences = preferences,
            modelAttempts = modelAttempts,
            workerProfileId = current.modelState.modelSelection.workerProfileId,
            availableProfiles = current.modelState.modelSelection.profiles,
        )
        statePort.updateRuntimeLimits(
            mainMaxSteps = agent.mainMaxSteps,
            subagentMaxSteps = agent.subagentMaxSteps,
            modelAttempts = model.modelAttempts,
        )
    }

    fun configureWorkerProfile(profileId: String?) {
        val current = state
        val model = LocalModelExecutionSettings.write(
            preferences = preferences,
            modelAttempts = current.modelState.modelAttempts,
            workerProfileId = profileId,
            availableProfiles = current.modelState.modelSelection.profiles,
        )
        statePort.updateWorkerProfile(model.workerProfileId)
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

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private data class PersonalizationWrite(
        val generation: Long,
        val profile: UserProfile,
        val receipt: CompletableDeferred<Boolean>?,
    )

    // CONFLATED avoids writing intermediate edits. A superseded caller must be
    // completed explicitly rather than waiting forever for a discarded value.
    private val personalizationWrites = Channel<PersonalizationWrite>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = { pending -> pending.receipt?.complete(false) },
    )

    init {
        scope.launch {
            var committedProfile = runCatching { userProfileStore.read() }.getOrDefault(UserProfile())
            // One writer preserves submission order; rapid edits retain only the latest pending
            // value. Runtime state is committed only after durable storage succeeds.
            for ((generation, profile, receipt) in personalizationWrites) {
                try {
                    userProfileStore.write(profile)
                    committedProfile = profile
                    val latest = personalizationGeneration.get() == generation
                    if (latest) {
                        statePort.updatePersonalization(
                            userRules = profile.customRules,
                            autoRecall = profile.autoRecall,
                            autoMemory = profile.autoMemory,
                        )
                    }
                    receipt?.complete(latest)
                } catch (cancelled: CancellationException) {
                    receipt?.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    if (personalizationGeneration.get() == generation) {
                        statePort.updatePersonalization(
                            userRules = committedProfile.customRules,
                            autoRecall = committedProfile.autoRecall,
                            autoMemory = committedProfile.autoMemory,
                        )
                        statePort.publishError(error.message ?: "个性化设置保存失败")
                    }
                    receipt?.completeExceptionally(error)
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
        val previousAgent = LocalAgentRuntimeSettings.snapshotForRollback(preferences)
        val previousModel = LocalModelExecutionSettings.snapshotForRollback(preferences)
        // Settings UI submits all four values as one decision and one disk commit.
        val committed = preferences.edit().also { editor ->
            LocalAgentRuntimeSettings.stage(editor, agent)
            LocalModelExecutionSettings.stage(editor, model)
        }.commit()
        if (!committed) {
            val failure = IllegalStateException("运行参数写入持久存储失败，状态保持原值")
            try {
                val rollback = preferences.edit()
                LocalAgentRuntimeSettings.stageRollback(rollback, previousAgent)
                LocalModelExecutionSettings.stageRollback(rollback, previousModel)
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
    private fun submitPersonalization(
        customRules: String,
        autoRecall: Boolean,
        autoMemory: Boolean,
        receipt: CompletableDeferred<Boolean>? = null,
    ) {
        val profile = UserProfile(
            customRules = customRules.trim().take(6_000),
            autoRecall = autoRecall,
            autoMemory = autoMemory,
        )
        val generation = personalizationGeneration.incrementAndGet()
        personalizationWrites.trySend(PersonalizationWrite(generation, profile, receipt)).getOrThrow()
    }

    /** Returns true only after this generation was durably written and still current. */
    suspend fun savePersonalization(
        customRules: String,
        autoRecall: Boolean,
        autoMemory: Boolean,
    ): Boolean {
        val receipt = CompletableDeferred<Boolean>()
        submitPersonalization(customRules, autoRecall, autoMemory, receipt)
        return receipt.await()
    }

    fun configurePersonalization(customRules: String, autoRecall: Boolean, autoMemory: Boolean) =
        submitPersonalization(customRules, autoRecall, autoMemory)
}

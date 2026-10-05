package com.labteto.dshmobile.local.settings

import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore

/** Settings-owned readable projection and explicit write boundary. */
internal data class LocalSettingsStateSnapshot(
    val modelState: LocalModelState,
    val mainMaxSteps: Int,
    val subagentMaxSteps: Int,
    val userRules: String,
    val autoRecall: Boolean,
    val autoMemory: Boolean,
    val chatStyleGuardEnabled: Boolean,
    val chatStyleGuardCustomPhrases: List<String>,
    val styleGuardHits: List<String>,
)

internal interface LocalSettingsStatePort {
    val value: LocalSettingsStateSnapshot
    fun updateRuntimeLimits(mainMaxSteps: Int, subagentMaxSteps: Int, modelAttempts: Int)
    fun updateWorkerProfile(profileId: String?)
    fun updatePersonalization(userRules: String, autoRecall: Boolean, autoMemory: Boolean)
    fun setChatStyleGuardEnabled(enabled: Boolean)
    fun setChatStyleGuardPhrases(phrases: List<String>)
    fun clearStyleGuardHits()
    fun recordStyleGuardHits(violations: List<String>, maxHits: Int)
    fun publishError(message: String)
}

internal fun localSettingsStatePort(runtimeStateStore: LocalRuntimeStateStore): LocalSettingsStatePort =
    object : LocalSettingsStatePort {
        override val value: LocalSettingsStateSnapshot
            get() = runtimeStateStore.state.value.let { state ->
                LocalSettingsStateSnapshot(
                    modelState = state.modelState,
                    mainMaxSteps = state.mainMaxSteps,
                    subagentMaxSteps = state.subagentMaxSteps,
                    userRules = state.userRules,
                    autoRecall = state.autoRecall,
                    autoMemory = state.autoMemory,
                    chatStyleGuardEnabled = state.chatStyleGuardEnabled,
                    chatStyleGuardCustomPhrases = state.chatStyleGuardCustomPhrases,
                    styleGuardHits = state.styleGuardHits,
                )
            }

        override fun updateRuntimeLimits(mainMaxSteps: Int, subagentMaxSteps: Int, modelAttempts: Int) {
            runtimeStateStore.projection.updateSettingsRuntimeLimits(
                mainMaxSteps = mainMaxSteps,
                subagentMaxSteps = subagentMaxSteps,
                modelAttempts = modelAttempts,
            )
        }

        override fun updateWorkerProfile(profileId: String?) {
            runtimeStateStore.projection.updateSettingsWorkerProfile(profileId)
        }

        override fun updatePersonalization(userRules: String, autoRecall: Boolean, autoMemory: Boolean) {
            runtimeStateStore.projection.updatePersonalization(
                userRules = userRules,
                autoRecall = autoRecall,
                autoMemory = autoMemory,
            )
        }

        override fun setChatStyleGuardEnabled(enabled: Boolean) {
            runtimeStateStore.projection.setChatStyleGuardEnabled(enabled)
        }

        override fun setChatStyleGuardPhrases(phrases: List<String>) {
            runtimeStateStore.projection.setChatStyleGuardPhrases(phrases)
        }

        override fun clearStyleGuardHits() {
            runtimeStateStore.projection.clearStyleGuardHits()
        }

        override fun recordStyleGuardHits(violations: List<String>, maxHits: Int) {
            runtimeStateStore.projection.recordStyleGuardHits(violations, maxHits)
        }

        override fun publishError(message: String) {
            runtimeStateStore.projection.publishError(message)
        }
    }

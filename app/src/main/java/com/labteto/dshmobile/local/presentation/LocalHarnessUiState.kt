package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.LocalGroupChatState
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Settings owns model identity, but ignores streaming, job and resource-counter churn. */
data class LocalHarnessSettingsState(
    val loading: Boolean = true,
    val model: String = "deepseek-flash",
    val baseUrl: String = "https://api.deepseek.com",
    val modelSelection: LocalModelSelectionState = LocalModelSelectionState(),
    val mainMaxSteps: Int = 16,
    val subagentMaxSteps: Int = 20,
    val modelAttempts: Int = 3,
    val imageInputMode: LocalImageInputMode = LocalImageInputMode.AUTO,
    val userRules: String = "",
    val autoRecall: Boolean = true,
    val autoMemory: Boolean = true,
    val chatStyleGuardEnabled: Boolean = true,
    val chatStyleGuardCustomPhrases: List<String> = emptyList(),
    val styleGuardHits: List<String> = emptyList(),
    val chatPersona: PersonaProfile = PersonaProfile(),
    val usage: DeepSeekUsageSnapshot = DeepSeekUsageSnapshot(),
    val error: String? = null,
) {
    val modelProfiles: List<LocalModelProfile> get() = modelSelection.profiles
}

/**
 * Tasks-facing projection of the local runtime.
 *
 * Automation editing only needs the active usage surface and chat target. Model streaming, tools,
 * background jobs and resource counters must not wake this screen.
 */
data class LocalHarnessTaskState(
    val sessionId: String = "",
    val usageMode: LocalUsageMode = LocalUsageMode.WORK,
    val groupChat: LocalGroupChatState = LocalGroupChatState(),
    val chatPersona: PersonaProfile = PersonaProfile(),
)

/**
 * Screen-shell projection. Keep this small: streaming text, jobs, tools and resource counters are
 * intentionally excluded so hot-path updates cannot invalidate the drawer and global dialogs.
 */
data class LocalHarnessShellState(
    val loading: Boolean = true,
    val sessionId: String = "",
    val sessions: List<LocalSessionSummary> = emptyList(),
    val usageMode: LocalUsageMode = LocalUsageMode.WORK,
    val running: Boolean = false,
    val groupChat: LocalGroupChatState = LocalGroupChatState(),
    val chatPersona: PersonaProfile = PersonaProfile(),
    val galleryId: String? = null,
    val galleryStoryId: String? = null,
    val workspacePath: String = "",
)

internal fun LocalHarnessState.toSettingsUiState(): LocalHarnessSettingsState =
    LocalHarnessSettingsState(
        loading = loading,
        model = modelState.model,
        baseUrl = modelState.baseUrl,
        modelSelection = modelState.modelSelection,
        mainMaxSteps = mainMaxSteps,
        subagentMaxSteps = subagentMaxSteps,
        modelAttempts = modelState.modelAttempts,
        imageInputMode = modelState.imageInputMode,
        userRules = userRules,
        autoRecall = autoRecall,
        autoMemory = autoMemory,
        chatStyleGuardEnabled = chatStyleGuardEnabled,
        chatStyleGuardCustomPhrases = chatStyleGuardCustomPhrases,
        styleGuardHits = styleGuardHits,
        chatPersona = chatPersona,
        usage = usage,
        error = error,
    )

internal fun LocalHarnessState.toShellUiState(): LocalHarnessShellState =
    LocalHarnessShellState(
        loading = loading,
        sessionId = sessionId,
        sessions = sessions,
        usageMode = usageMode,
        running = running,
        groupChat = groupChat,
        chatPersona = chatPersona,
        galleryId = galleryId,
        galleryStoryId = galleryStoryId,
        workspacePath = workspacePath,
    )

internal fun StateFlow<LocalHarnessState>.projectShellState(
    scope: CoroutineScope,
): StateFlow<LocalHarnessShellState> =
    map { it.toShellUiState() }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = value.toShellUiState(),
        )

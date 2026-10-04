package com.labteto.dshmobile.local.runtime

import android.app.ActivityManager
import android.content.Context
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalInteractionCoordinator
import com.labteto.dshmobile.local.localHistoryBudgetFor
import com.labteto.dshmobile.local.localResourceBudgetForMemoryClass
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide owner of shared local runtime state and scarce-resource scheduling.
 *
 * Feature capabilities may observe shared resource snapshots, but this Runtime owner never imports
 * Feature internals. Engine bootstrap initializes the first visible snapshot exactly once.
 */
@Singleton
class LocalRuntimeStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val mutable = MutableStateFlow(LocalHarnessState())
    private val sendFeedbackMutable = MutableStateFlow(LocalSendFeedbackState())
    private val resourceObservers = CopyOnWriteArrayList<(HarnessResourceSnapshot) -> Unit>()

    internal val memoryClassMb =
        context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256
    internal val resourceBudget = localResourceBudgetForMemoryClass(memoryClassMb)
    internal val resourceScheduler = HarnessResourceScheduler(
        budget = resourceBudget,
        onChanged = ::publishResourceSnapshot,
    )

    internal val foregroundInteractions = LocalInteractionCoordinator(mutable)
    internal val streamingPreviewStore = LocalStreamingPreviewStore()
    @Volatile private var initialized = false
    @Volatile private var foregroundSessionId: String? = null

    internal val state: StateFlow<LocalHarnessState> = mutable.asStateFlow()
    internal val sendFeedbackState: StateFlow<LocalSendFeedbackState> = sendFeedbackMutable.asStateFlow()
    internal val mutableState: MutableStateFlow<LocalHarnessState>
        get() = mutable

    internal val currentSessionId: String
        get() = foregroundSessionId ?: error("LocalRuntimeStateStore 尚未初始化")

    internal fun observeResourceSnapshots(observer: (HarnessResourceSnapshot) -> Unit) {
        resourceObservers += observer
        runCatching { observer(resourceScheduler.snapshot()) }
    }

    internal fun resourceSnapshot(): HarnessResourceSnapshot = resourceScheduler.snapshot()

    internal fun contextBudgetCharsFor(
        state: LocalHarnessState,
        snapshot: HarnessResourceSnapshot,
    ): Int = localHistoryBudgetFor(
        memoryClassMb = memoryClassMb,
        pressure = snapshot.pressure,
        model = state.modelState.model,
        baseUrl = state.modelState.baseUrl,
        contextWindowTokensOverride =
            state.modelState.modelSelection.activeProfile?.contextWindowTokensOverride,
    ).maxHistoryChars

    internal suspend fun <T> withModelRequestResource(block: suspend () -> T): T =
        resourceScheduler.withResource(
            HarnessResourceKind.MODEL_REQUEST,
            "automation-planning",
            block,
        )

    @Synchronized
    internal fun initialize(initialState: LocalHarnessState): MutableStateFlow<LocalHarnessState> {
        check(!initialized) { "LocalRuntimeStateStore 已完成初始化" }
        require(initialState.sessionId.isNotBlank()) { "初始会话编号不能为空" }
        foregroundSessionId = initialState.sessionId
        mutable.value = projectResourceSnapshot(initialState, resourceScheduler.snapshot())
        initialized = true
        return mutable
    }

    internal fun publishSendFeedback(feedback: LocalSendFeedbackState) {
        sendFeedbackMutable.value = feedback
    }

    internal fun clearSendFeedback() {
        sendFeedbackMutable.value = LocalSendFeedbackState()
    }

    @Synchronized
    internal fun activateSession(sessionId: String) {
        check(initialized) { "LocalRuntimeStateStore 尚未初始化" }
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        foregroundSessionId = sessionId
    }

    private fun publishResourceSnapshot(snapshot: HarnessResourceSnapshot) {
        mutable.update { current -> projectResourceSnapshot(current, snapshot) }
        resourceObservers.forEach { observer -> runCatching { observer(snapshot) } }
    }

    private fun projectResourceSnapshot(
        state: LocalHarnessState,
        snapshot: HarnessResourceSnapshot,
    ): LocalHarnessState = state.copy(
        kernel = state.kernel.copy(
            resources = snapshot.toLocalHarnessResourceState(state.usageMode),
            contextBudgetChars = contextBudgetCharsFor(state, snapshot),
        ),
    )
}

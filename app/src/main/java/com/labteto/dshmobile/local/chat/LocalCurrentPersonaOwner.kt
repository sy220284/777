package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.CurrentPersonaUiPort
import com.labteto.dshmobile.uicontract.UiCurrentPersonaReceipt
import com.labteto.dshmobile.uicontract.UiCurrentPersonaSnapshot
import com.labteto.dshmobile.uicontract.UiCurrentPersonaTarget
import com.labteto.dshmobile.uicontract.UiPersonaDraft
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The existing Chat transaction owns active profile writes. No UI-side stores or synchronization. */
@Singleton
class LocalCurrentPersonaOwner @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val tuning: LocalCharacterBehaviorTuningCoordinator,
    private val suggestions: PersonaAutoFillService,
    private val session: LocalSessionRuntime,
) : CurrentPersonaUiPort {
    private fun available(current: LocalHarnessState) = !state.sessionTransitioning &&
        !current.loading && !current.kernel.running && current.usageMode == LocalUsageMode.CHAT &&
        !current.chat.groupChat.enabled

    private fun currentTarget(current: LocalHarnessState): UiCurrentPersonaTarget? = state.uiBindingIdentity(current)?.let {
        UiCurrentPersonaTarget(it, current.chat.chatPersona.id, current.chat.chatPersona.updatedAt,
            current.chat.chatPersona.behaviorTuning.updatedAt)
    }

    override fun read(): UiCurrentPersonaReceipt = try {
        synchronized(state.foregroundRunHandle.lock) {
            val current = state.state.value
            val target = currentTarget(current)
            if (!available(current) || target == null) UiCurrentPersonaReceipt.Rejected
            else {
                val tuning = current.chat.chatPersona.behaviorTuning
                val range = com.labteto.dshmobile.local.model.LocalEffectiveModelContracts.resolve(
                    current.modelState.model, current.modelState.baseUrl).temperature
                val position = if (range == null) tuning.samplingTemperaturePosition ?: tuning.expressionVariation
                    else tuning.temperaturePosition(range)
                UiCurrentPersonaReceipt.Loaded(UiCurrentPersonaSnapshot(target, current.chat.chatPersona.authoringDraft(), position.coerceIn(0, 100)))
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { UiCurrentPersonaReceipt.Failed(error.message) }

    override suspend fun save(target: UiCurrentPersonaTarget, draft: UiPersonaDraft) = guarded {
        val before = synchronized(state.foregroundRunHandle.lock) {
            state.state.value.takeIf { available(it) && currentTarget(it) == target }
        } ?: return@guarded UiCurrentPersonaReceipt.Rejected
        val result = tuning.configure(draft.applyTo(before.chat.chatPersona)) { commit ->
            synchronized(state.foregroundRunHandle.lock) {
                val current = state.state.value
                check(available(current) && currentTarget(current) == target) { "会话或人物已变化，请重新打开人物设置" }
                commit()
            }
        }
        result.fold(onSuccess = { UiCurrentPersonaReceipt.Saved },
            onFailure = { UiCurrentPersonaReceipt.Failed(it.message) })
    }

    override suspend fun suggest(target: UiCurrentPersonaTarget, draft: UiPersonaDraft, description: String) = guarded {
        val before = synchronized(state.foregroundRunHandle.lock) {
            state.state.value.takeIf { available(it) && currentTarget(it) == target }
        } ?: return@guarded UiCurrentPersonaReceipt.Rejected
        if (!before.modelState.configured) return@guarded UiCurrentPersonaReceipt.Failed("请先配置模型，再补全人物资料")
        val recent = session.transcriptTailForUi(before.sessionId, 12)
        fun stillCurrent() = synchronized(state.foregroundRunHandle.lock) {
            val current = state.state.value
            available(current) && currentTarget(current) == target && current.modelState == before.modelState
        }
        if (!stillCurrent()) return@guarded UiCurrentPersonaReceipt.Rejected
        val proposed = suggestions.generate(before.modelState.model, before.modelState.baseUrl,
            before.modelState.modelSelection.activeProfileId, draft.applyTo(before.chat.chatPersona), recent, description)
        if (!stillCurrent()) UiCurrentPersonaReceipt.Rejected
        else UiCurrentPersonaReceipt.Suggested(proposed.authoringDraft())
    }

    private suspend fun guarded(block: suspend () -> UiCurrentPersonaReceipt): UiCurrentPersonaReceipt = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { UiCurrentPersonaReceipt.Failed(error.message) }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalCurrentPersonaBindings {
    @dagger.Binds @Singleton
    abstract fun currentPersona(owner: LocalCurrentPersonaOwner): CurrentPersonaUiPort
}

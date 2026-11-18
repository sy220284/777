package com.labteto.dshmobile.local

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.local.work.WorkAutomationApprovalResumeLedger
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.AutomationUiDefaults
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.automation.validAutomationCommandSchedule
import com.labteto.dshmobile.automation.newAutomationTaskId
import com.labteto.dshmobile.local.automation.AutomationPlanningService
import com.labteto.dshmobile.local.presentation.LocalAutomationPolicyProjection
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.AutomationUiCommand
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationContext
import com.labteto.dshmobile.uicontract.UiAutomationSnapshot
import com.labteto.dshmobile.uicontract.UiAutomationSchedule
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import com.labteto.dshmobile.uicontract.PlanChatAutomationCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** Application composition joins existing Automation ownership with the shared foreground fence. */
@Singleton
class LocalAutomationUiPortComposition @Inject internal constructor(
    private val scheduler: HarnessAutomationScheduler,
    private val state: LocalRuntimeStateStore,
    private val planning: AutomationPlanningService,
    private val approvalLedger: WorkAutomationApprovalResumeLedger,
) : AutomationUiPort {
    private fun context(snapshot: LocalHarnessState) = UiAutomationContext(state.uiBindingIdentity(snapshot),
        snapshot.usageMode, snapshot.chat.groupChat.enabled, snapshot.chat.chatPersona.name)
    override val snapshots = flow {
        // Task storage is initialized only during collection on IO, never during Hilt creation.
        emitAll(combine(state.state, scheduler.tasks) { snapshot, tasks ->
            UiAutomationSnapshot(context(snapshot), tasks)
        }.distinctUntilChanged())
    }.flowOn(Dispatchers.IO)
    override fun context() = synchronized(state.foregroundRunHandle.lock) { context(state.state.value) }
    override val planningChanges = planning.revisions.map { it.toString() }.distinctUntilChanged()
    override fun planningRevision() = planning.currentRevision().toString()
    override fun snapshot(): UiAutomationSnapshot {
        val tasks = scheduler.list()
        return UiAutomationSnapshot(context(), tasks)
    }
    override fun submit(command: AutomationUiCommand): AutomationUiReceipt = try {
        synchronized(state.foregroundRunHandle.lock) {
            val applied = when (command) {
                is AutomationUiCommand.Create -> {
                    if (command.schedule.prompt.isBlank() || !validAutomationCommandSchedule(
                            command.schedule.mode, command.schedule.scheduleType,
                            command.schedule.firstRunAt, command.schedule.recurringMinutes,
                            System.currentTimeMillis(), command.schedule.silenceMinutes,
                            command.schedule.windowStartMinuteOfDay,
                            command.schedule.windowEndMinuteOfDay,
                        )) return AutomationUiReceipt.Rejected(UiAutomationRejection.INVALID_INPUT)
                    if (command.schedule.mode == AutomationMode.CHAT &&
                        (state.sessionTransitioning || state.uiBindingIdentity(state.state.value) != command.identity ||
                            !context(state.state.value).canPlanChat)) {
                        return AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_CONTEXT)
                    }
                    create(command.schedule)
                }
                is AutomationUiCommand.Update -> {
                    if (command.schedule.prompt.isBlank() || !validAutomationCommandSchedule(
                            command.schedule.mode, command.schedule.scheduleType,
                            command.schedule.firstRunAt, command.schedule.recurringMinutes,
                            System.currentTimeMillis(), command.schedule.silenceMinutes,
                            command.schedule.windowStartMinuteOfDay,
                            command.schedule.windowEndMinuteOfDay,
                        )) return AutomationUiReceipt.Rejected(UiAutomationRejection.INVALID_INPUT)
                    val task = scheduler.list().firstOrNull { it.id == command.id }
                        ?: return AutomationUiReceipt.Rejected(UiAutomationRejection.MISSING)
                    if (task.scheduleGeneration != command.expectedGeneration || task.mode != command.schedule.mode)
                        return AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_TASK)
                    update(command.id, command.schedule, command.expectedGeneration)
                }
                is AutomationUiCommand.UpdatePolicy -> {
                    val task = scheduler.list().firstOrNull { it.id == command.id }
                        ?: return AutomationUiReceipt.Rejected(UiAutomationRejection.MISSING)
                    if (task.scheduleGeneration != command.expectedGeneration)
                        return AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_TASK)
                    if (task.mode != AutomationMode.CHAT)
                        return AutomationUiReceipt.Rejected(UiAutomationRejection.INVALID_INPUT)
                    with(command.policy) { updateChatPolicy(task, quietHoursEnabled, quietStartHour, quietStartMinute,
                        quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered, notify) }
                }
                is AutomationUiCommand.Delete -> scheduler.cancelTask(command.id)
                is AutomationUiCommand.Pause -> scheduler.pauseTask(command.id)
                is AutomationUiCommand.Resume -> scheduler.resumeTask(command.id)
                is AutomationUiCommand.RunNow -> scheduler.runTaskNow(command.id)
                is AutomationUiCommand.ApproveBlocked -> {
                    val task = scheduler.list().firstOrNull { it.id == command.id }
                        ?: return AutomationUiReceipt.Rejected(UiAutomationRejection.MISSING)
                    if (task.scheduleGeneration != command.expectedGeneration)
                        return AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_TASK)
                    if (task.mode != AutomationMode.WORK || task.status != AutomationStatus.BLOCKED ||
                        task.workSessionId.isNullOrBlank())
                        return AutomationUiReceipt.Rejected(UiAutomationRejection.INVALID_INPUT)
                    approvalLedger.authorize(requireNotNull(task.workSessionId)) &&
                        scheduler.runTaskNow(task.id, approvalResume = true)
                }
            }
            if (applied) AutomationUiReceipt.Applied()
            else AutomationUiReceipt.Rejected(UiAutomationRejection.OWNER_REJECTED)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        AppLog.failure("TaskScheduling", "submit_automation_command", failure)
        AutomationUiReceipt.Failed(failure.message)
    }

    override suspend fun planAndSave(command: PlanChatAutomationCommand): AutomationUiReceipt {
        val captured = snapshot()
        if (captured.context.identity != command.identity || !captured.context.canPlanChat)
            return AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_CONTEXT)
        if (command.input.isBlank()) return AutomationUiReceipt.Rejected(UiAutomationRejection.INVALID_INPUT)
        val editing = command.editingTaskId?.let { id -> captured.tasks.firstOrNull {
            it.id == id && it.mode == AutomationMode.CHAT && it.targetSessionId == command.identity.sessionId
        } ?: return AutomationUiReceipt.Rejected(UiAutomationRejection.MISSING) }
        return try {
            val draft = planning.plan(command.input)
            synchronized(state.foregroundRunHandle.lock) {
                if (state.sessionTransitioning || state.uiBindingIdentity(state.state.value) != command.identity ||
                    draft.sourceSessionId != command.identity.sessionId || !planning.isCurrent(draft)) {
                    return AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_CONTEXT)
                }
                val spec = UiAutomationSchedule(draft.prompt, draft.firstRunAt, draft.recurringMinutes,
                    AutomationMode.CHAT, draft.scheduleType, draft.silenceMinutes,
                    draft.windowStartMinuteOfDay, draft.windowEndMinuteOfDay,
                    quietHoursEnabled = editing?.quietHoursEnabled ?: true,
                    quietStartHour = editing?.quietStartHour ?: AutomationUiDefaults.DEFAULT_QUIET_START_HOUR,
                    quietStartMinute = editing?.quietStartMinute ?: AutomationUiDefaults.DEFAULT_QUIET_START_MINUTE,
                    quietEndHour = editing?.quietEndHour ?: AutomationUiDefaults.DEFAULT_QUIET_END_HOUR,
                    quietEndMinute = editing?.quietEndMinute ?: AutomationUiDefaults.DEFAULT_QUIET_END_MINUTE,
                    proactiveMinGapMinutes = editing?.proactiveMinGapMinutes ?: AutomationUiDefaults.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
                    proactiveMaxUnanswered = editing?.proactiveMaxUnanswered ?: AutomationUiDefaults.DEFAULT_PROACTIVE_MAX_UNANSWERED,
                    notify = editing?.notify ?: true)
                submit(if (editing == null) AutomationUiCommand.Create(command.identity, spec)
                    else AutomationUiCommand.Update(editing.id, editing.scheduleGeneration, spec))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AutomationUiReceipt.Failed(failure.message) }
    }
    private fun create(spec: UiAutomationSchedule) = with(spec) {
        createAt(prompt, firstRunAt, recurringMinutes, mode, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled, quietStartHour, quietStartMinute,
            quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered, notify)
    }
    private fun update(id: String, spec: UiAutomationSchedule, expectedGeneration: Long) = with(spec) {
        updateTask(id, prompt, firstRunAt, recurringMinutes, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled, quietStartHour, quietStartMinute,
            quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered, notify, expectedGeneration)
    }
    private fun updateTask(
        id: String,
        prompt: String,
        firstRunAt: Long,
        recurringMinutes: Long?,
        scheduleType: AutomationScheduleType,
        silenceMinutes: Long?,
        windowStartMinuteOfDay: Int?,
        windowEndMinuteOfDay: Int?,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
        notify: Boolean? = null,
        expectedGeneration: Long,
    ): Boolean = scheduler.updateTask(
            id, prompt, firstRunAt, recurringMinutes, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled,
            quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
            proactiveMinGapMinutes, proactiveMaxUnanswered, notify, expectedGeneration,
        )
    private fun createAt(
        prompt: String,
        firstRunAt: Long,
        recurringMinutes: Long?,
        mode: AutomationMode,
        scheduleType: AutomationScheduleType,
        silenceMinutes: Long? = null,
        windowStartMinuteOfDay: Int? = null,
        windowEndMinuteOfDay: Int? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = LocalAutomationPolicyProjection.defaults.quietStartHour,
        quietStartMinute: Int = LocalAutomationPolicyProjection.defaults.quietStartMinute,
        quietEndHour: Int = LocalAutomationPolicyProjection.defaults.quietEndHour,
        quietEndMinute: Int = LocalAutomationPolicyProjection.defaults.quietEndMinute,
        proactiveMinGapMinutes: Long = LocalAutomationPolicyProjection.defaults.proactiveMinGapMinutes,
        proactiveMaxUnanswered: Int = LocalAutomationPolicyProjection.defaults.proactiveMaxUnanswered,
        notify: Boolean = true,
    ): Boolean {
        // The public submit command has already validated schedule and session identity.
        // Only the scheduler performs the durable mutation; exceptions propagate to
        // AutomationUiReceipt.Failed with their actual cause.
        val snapshot = state.state.value
            val id = newAutomationTaskId()
            val targetSessionId = snapshot.sessionId.takeIf { mode == AutomationMode.CHAT }
            val actorName = snapshot.chat.chatPersona.name.takeIf {
                mode == AutomationMode.CHAT && it.isNotBlank()
            }
            when {
                scheduleType == AutomationScheduleType.WINDOW -> scheduler.scheduleWindow(
                    id, prompt.trim(), requireNotNull(windowStartMinuteOfDay),
                    requireNotNull(windowEndMinuteOfDay), notify, requireNotNull(targetSessionId),
                    actorName, quietHoursEnabled, quietStartHour, quietStartMinute,
                    quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                scheduleType == AutomationScheduleType.SILENCE -> scheduler.scheduleSilence(
                    id, prompt.trim(), requireNotNull(silenceMinutes), notify,
                    requireNotNull(targetSessionId), actorName, quietHoursEnabled,
                    quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
                    proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                recurringMinutes == null -> scheduler.scheduleOnce(
                    id, prompt.trim(), firstRunAt, notify, mode, targetSessionId, actorName,
                    quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour,
                    quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                else -> scheduler.schedulePeriodic(
                    id, prompt.trim(), recurringMinutes, firstRunAt, notify, mode,
                    targetSessionId, actorName, quietHoursEnabled, quietStartHour,
                    quietStartMinute, quietEndHour, quietEndMinute, proactiveMinGapMinutes,
                    proactiveMaxUnanswered,
                    scheduleType,
                )
            }
        return true
    }
    private fun updateChatPolicy(
        task: AutomationTask,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
        notify: Boolean,
    ): Boolean = scheduler.updateChatInteractionPolicy(
            id = task.id,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
            notify = notify,
            expectedGeneration = task.scheduleGeneration,
        )

}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalAutomationUiBindings {
    @dagger.Binds @Singleton
    abstract fun automation(implementation: LocalAutomationUiPortComposition): AutomationUiPort
}

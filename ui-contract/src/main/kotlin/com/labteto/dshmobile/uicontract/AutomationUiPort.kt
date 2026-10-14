package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.AutomationUiDefaults
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.coroutines.flow.Flow

/** A projection of the existing scheduler and foreground binding, never another task ledger. */
data class UiAutomationContext(
    val identity: UiSessionIdentity?,
    val usageMode: LocalUsageMode,
    val groupChatEnabled: Boolean,
    val personaName: String,
) {
    val sessionId: String get() = identity?.sessionId.orEmpty()
    val canPlanChat: Boolean get() = identity != null && usageMode == LocalUsageMode.CHAT && !groupChatEnabled
}
data class UiAutomationSnapshot(val context: UiAutomationContext, val tasks: List<AutomationTask>)
data class UiAutomationSchedule(
    val prompt: String, val firstRunAt: Long, val recurringMinutes: Long?,
    val mode: AutomationMode, val scheduleType: AutomationScheduleType,
    val silenceMinutes: Long? = null, val windowStartMinuteOfDay: Int? = null, val windowEndMinuteOfDay: Int? = null,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = AutomationUiDefaults.DEFAULT_QUIET_START_HOUR,
    val quietStartMinute: Int = AutomationUiDefaults.DEFAULT_QUIET_START_MINUTE,
    val quietEndHour: Int = AutomationUiDefaults.DEFAULT_QUIET_END_HOUR,
    val quietEndMinute: Int = AutomationUiDefaults.DEFAULT_QUIET_END_MINUTE,
    val proactiveMinGapMinutes: Long = AutomationUiDefaults.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
    val proactiveMaxUnanswered: Int = AutomationUiDefaults.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    val notify: Boolean = true,
)
data class UiAutomationInteractionPolicy(
    val quietHoursEnabled: Boolean, val quietStartHour: Int, val quietStartMinute: Int,
    val quietEndHour: Int, val quietEndMinute: Int,
    val proactiveMinGapMinutes: Long, val proactiveMaxUnanswered: Int, val notify: Boolean,
)
sealed interface AutomationUiCommand {
    data class Create(val identity: UiSessionIdentity?, val schedule: UiAutomationSchedule) : AutomationUiCommand
    data class Update(val id: String, val expectedGeneration: Long, val schedule: UiAutomationSchedule) : AutomationUiCommand
    data class UpdatePolicy(val id: String, val expectedGeneration: Long, val policy: UiAutomationInteractionPolicy) : AutomationUiCommand
    data class Delete(val id: String) : AutomationUiCommand
    data class Pause(val id: String) : AutomationUiCommand
    data class Resume(val id: String) : AutomationUiCommand
    data class RunNow(val id: String) : AutomationUiCommand
}
enum class UiAutomationRejection { STALE_CONTEXT, STALE_TASK, INVALID_INPUT, MISSING, OWNER_REJECTED }
sealed interface AutomationUiReceipt {
    /** RunNow admission is distinct from a completed/verified run. */
    data class Applied(val taskId: String? = null) : AutomationUiReceipt
    data class Rejected(val reason: UiAutomationRejection) : AutomationUiReceipt
    data class Failed(val message: String?) : AutomationUiReceipt
}
data class PlanChatAutomationCommand(val identity: UiSessionIdentity, val input: String, val editingTaskId: String? = null)
interface AutomationUiPort {
    val snapshots: Flow<UiAutomationSnapshot>
    val planningChanges: Flow<String>
    fun planningRevision(): String
    fun snapshot(): UiAutomationSnapshot
    fun submit(command: AutomationUiCommand): AutomationUiReceipt
    suspend fun planAndSave(command: PlanChatAutomationCommand): AutomationUiReceipt
}

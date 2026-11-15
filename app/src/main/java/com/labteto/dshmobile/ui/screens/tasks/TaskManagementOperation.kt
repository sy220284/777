package com.labteto.dshmobile.ui.screens.tasks

// FEATURE_TREE: {"id":"automation.management-receipts","path":"第一层｜产品功能与界面 / 自动化功能 / 自动任务目录 / 任务操作异常与受理回执","owner":"AUTOMATION","entry":"runTaskManagementOperation","test":"app/src/test/java/com/labteto/dshmobile/ui/screens/tasks/TaskManagementOperationTest.kt#failedAcknowledgementReadsCurrentFactsWithoutReplayingMutation"}
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TaskManagementResult(
    val accepted: Boolean,
    val failed: Boolean,
    val tasks: List<AutomationTask>?,
    val failureMessage: String? = null,
)

/** A submitted command is never retried to compensate for a lost acknowledgement. */
internal suspend fun runTaskManagementOperation(
    operation: () -> AutomationUiReceipt,
    readTasks: () -> List<AutomationTask>,
): TaskManagementResult = withContext(Dispatchers.IO) {
    val receipt = try { operation() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        AppLog.failure("TaskScheduling", "manage_task", error)
        AutomationUiReceipt.Failed(error.message)
    }
    var failed = receipt is AutomationUiReceipt.Failed
    var message = when (receipt) {
        is AutomationUiReceipt.Applied -> null
        is AutomationUiReceipt.Failed -> receipt.message ?: "任务操作失败，请查看诊断信息"
        is AutomationUiReceipt.Rejected -> when (receipt.reason) {
            UiAutomationRejection.STALE_CONTEXT -> "会话已切换，请在当前会话重新操作"
            UiAutomationRejection.STALE_TASK -> "任务已更新，请刷新后重新修改"
            UiAutomationRejection.MISSING -> "任务已不存在，请刷新任务列表"
            UiAutomationRejection.INVALID_INPUT -> "任务参数无效，请核对时间与执行条件"
            UiAutomationRejection.OWNER_REJECTED -> "任务未受理，请刷新查看当前状态"
        }
    }
    val tasks = try { readTasks() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        AppLog.failure("TaskScheduling", "refresh_after_management", error)
        failed = true
        if (message == null) message = error.message
        null
    }
    TaskManagementResult(receipt is AutomationUiReceipt.Applied, failed, tasks, message)
}

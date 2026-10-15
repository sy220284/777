package com.labteto.dshmobile.ui.screens.tasks

// FEATURE_TREE: {"id":"automation.management-receipts","path":"第一层｜产品功能与界面 / 自动化功能 / 自动任务目录 / 任务操作异常与受理回执","owner":"AUTOMATION","entry":"runTaskManagementOperation","test":"app/src/test/java/com/labteto/dshmobile/ui/screens/tasks/TaskManagementOperationTest.kt#failedAcknowledgementReadsCurrentFactsWithoutReplayingMutation"}
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.observability.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TaskManagementResult(
    val accepted: Boolean,
    val failed: Boolean,
    val tasks: List<AutomationTask>?,
)

/** A failed acknowledgement must not automatically replay a possibly committed command. */
internal suspend fun runTaskManagementOperation(
    operation: () -> Boolean,
    readTasks: () -> List<AutomationTask>,
): TaskManagementResult = withContext(Dispatchers.IO) {
    var failed = false
    val accepted = try { operation() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        AppLog.failure("TaskScheduling", "manage_task", error)
        failed = true
        false
    }
    val tasks = try { readTasks() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        AppLog.failure("TaskScheduling", "refresh_after_management", error)
        failed = true
        null
    }
    TaskManagementResult(accepted, failed, tasks)
}

package com.labteto.dshmobile.automation

import android.content.Context
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.stableNotificationId
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.labteto.dshmobile.harness.capability.HarnessScheduler
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.harness.tools.simpleToolProperties
import com.labteto.dshmobile.local.LocalAutomationWorkException
import com.labteto.dshmobile.local.LocalHarnessBlockedException
import com.labteto.dshmobile.local.automation.LocalAutomationRuntime
import com.labteto.dshmobile.local.truncateWithoutSplittingSurrogatePair
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.charset.StandardCharsets
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AutomationPlugin(
    private val scheduler: HarnessAutomationScheduler,
    private val store: AutomationStore,
) : HarnessPlugin {
    override val id: String = "android-automation"

    override suspend fun install(context: HarnessContext) {
        register(
            context,
            name = "schedule_task",
            description = "创建一次性 Android 后台 Harness 任务",
            properties = mapOf(
                "id" to "string",
                "prompt" to "string",
                "delay_minutes" to "integer",
                "run_at_epoch_ms" to "integer",
                "notify" to "boolean",
            ),
            required = setOf("id", "prompt"),
            access = ToolAccess.SESSION_WRITE,
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            val now = System.currentTimeMillis()
            val runAt = parseOptionalAutomationLong(
                input["run_at_epoch_ms"]?.jsonPrimitive?.content,
                "run_at_epoch_ms",
            ) ?: checkedAutomationFutureMillis(
                now,
                parseOptionalAutomationLong(
                    input["delay_minutes"]?.jsonPrimitive?.content,
                    "delay_minutes",
                ) ?: 0L,
                "任务延迟",
            )
            val id = input.required("id")
            scheduler.scheduleOnce(
                id = id,
                prompt = input.required("prompt"),
                triggerAtMillis = runAt,
                notify = input["notify"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            )
            "已创建任务 $id，计划时间：$runAt"
        }
        register(
            context,
            name = "schedule_recurring_task",
            description = "创建可跨进程恢复的周期 Harness 任务，Android 最短周期 15 分钟",
            properties = mapOf(
                "id" to "string",
                "prompt" to "string",
                "interval_minutes" to "integer",
                "delay_minutes" to "integer",
                "notify" to "boolean",
            ),
            required = setOf("id", "prompt", "interval_minutes"),
            access = ToolAccess.SESSION_WRITE,
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            val first = checkedAutomationFutureMillis(
                System.currentTimeMillis(),
                parseOptionalAutomationLong(
                    input["delay_minutes"]?.jsonPrimitive?.content,
                    "delay_minutes",
                ) ?: 0L,
                "首次任务延迟",
            )
            val id = input.required("id")
            scheduler.schedulePeriodic(
                id = id,
                prompt = input.required("prompt"),
                intervalMinutes = parseOptionalAutomationLong(
                    input.required("interval_minutes"),
                    "interval_minutes",
                ) ?: error("缺少参数：interval_minutes"),
                firstRunAtMillis = first,
                notify = input["notify"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            )
            "已创建周期任务 $id"
        }
        register(
            context,
            name = "scheduled_task_list",
            description = "列出 Android 后台 Harness 任务及最近结果",
            access = ToolAccess.READ_ONLY,
            approval = ToolApprovalPolicy.NEVER,
        ) {
            val tasks = store.list()
            if (tasks.isEmpty()) "暂无后台任务" else tasks.joinToString("\n\n") { task ->
                buildString {
                    appendLine("id=${task.id}")
                    appendLine("status=${task.status}")
                    appendLine("next_run_at=${task.nextRunAt}")
                    appendLine("recurring_minutes=${task.recurringMinutes ?: 0}")
                    task.lastResult?.let { appendLine("last_result=${it.take(1_000)}") }
                    task.lastError?.let { appendLine("last_error=${it.take(1_000)}") }
                }.trimEnd()
            }
        }
        register(
            context,
            name = "cancel_scheduled_task",
            description = "取消并删除 Android 后台 Harness 任务",
            properties = mapOf("id" to "string"),
            required = setOf("id"),
            access = ToolAccess.SESSION_WRITE,
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            scheduler.cancelTask(input.required("id")).toString()
        }
        context.capabilities.register(
            com.labteto.dshmobile.harness.capability.CapabilityDescriptor("android-scheduler"),
            scheduler,
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        listOf(
            "schedule_task",
            "schedule_recurring_task",
            "scheduled_task_list",
            "cancel_scheduled_task",
        ).forEach(context.tools::unregister)
        context.capabilities.unregister("android-scheduler")
    }

    private fun register(
        context: HarnessContext,
        name: String,
        description: String,
        properties: Map<String, String> = emptyMap(),
        required: Set<String> = emptySet(),
        access: ToolAccess,
        approval: ToolApprovalPolicy,
        execute: suspend (JsonObject) -> String,
    ) {
        context.tools.register(
            HarnessTool(
                name = name,
                schema = functionToolSchema(
                    name = name,
                    description = description,
                    properties = simpleToolProperties(properties),
                    required = required,
                ),
                access = access,
                approvalPolicy = approval,
                exposure = ToolExposure.OPTIONAL,
                metadata = automationToolMetadata(name),
                executor = HarnessToolExecutor { _, input, _ ->
                    ToolResult(execute(input))
                },
            ),
        )
    }

    private fun JsonObject.required(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: error("缺少参数：$key")
}

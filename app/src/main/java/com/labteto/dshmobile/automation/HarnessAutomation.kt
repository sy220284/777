package com.labteto.dshmobile.automation

import android.content.Context
import com.labteto.dshmobile.R
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
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.local.LocalHarnessBlockedException
import com.labteto.dshmobile.local.LocalHarnessBusyException
import com.labteto.dshmobile.local.LocalHarnessEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
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

@Serializable
data class AutomationTask(
    val id: String,
    val prompt: String,
    val createdAt: Long,
    val nextRunAt: Long,
    val recurringMinutes: Long? = null,
    val notify: Boolean = true,
    /** Dedicated Work-mode session that owns this task's run history and artifacts. */
    val workSessionId: String? = null,
    val status: String = "scheduled",
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
)

@Serializable
private data class AutomationDocument(
    val version: Int = 1,
    val tasks: List<AutomationTask> = emptyList(),
)

@Singleton
class AutomationStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "local-harness/automations.json")

    @Synchronized
    fun list(): List<AutomationTask> = read().tasks.sortedBy { it.nextRunAt }

    @Synchronized
    fun get(id: String): AutomationTask? = read().tasks.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(task: AutomationTask) {
        val current = read().tasks.filterNot { it.id == task.id } + task
        write(AutomationDocument(tasks = current))
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val document = read()
        val remaining = document.tasks.filterNot { it.id == id }
        if (remaining.size == document.tasks.size) return false
        write(document.copy(tasks = remaining))
        return true
    }

    @Synchronized
    fun update(id: String, transform: (AutomationTask) -> AutomationTask): AutomationTask? {
        val document = read()
        val task = document.tasks.firstOrNull { it.id == id } ?: return null
        val updated = transform(task)
        write(document.copy(tasks = document.tasks.map { if (it.id == id) updated else it }))
        return updated
    }

    private fun read(): AutomationDocument {
        if (!file.isFile) return AutomationDocument()
        return runCatching {
            json.decodeFromString(AutomationDocument.serializer(), file.readText())
        }.getOrElse {
            val corrupt = File(file.parentFile, "automations.corrupt-${System.currentTimeMillis()}.json")
            runCatching { file.copyTo(corrupt, overwrite = true) }
            AutomationDocument()
        }
    }

    private fun write(document: AutomationDocument) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(AutomationDocument.serializer(), document))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}

@Singleton
class HarnessAutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutomationStore,
) : HarnessScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override suspend fun schedule(id: String, triggerAtMillis: Long, payload: String) {
        scheduleOnce(id, payload, triggerAtMillis, notify = true)
    }

    override suspend fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
        store.remove(id)
    }

    fun scheduleOnce(
        id: String,
        prompt: String,
        triggerAtMillis: Long,
        notify: Boolean,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        val now = System.currentTimeMillis()
        val runAt = triggerAtMillis.coerceAtLeast(now)
        store.upsert(
            AutomationTask(
                id = id,
                prompt = prompt,
                createdAt = now,
                nextRunAt = runAt,
                notify = notify,
            ),
        )
        val request = OneTimeWorkRequestBuilder<HarnessAutomationWorker>()
            .setInitialDelay((runAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_TASK_ID, id).build())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(
        id: String,
        prompt: String,
        intervalMinutes: Long,
        firstRunAtMillis: Long = System.currentTimeMillis(),
        notify: Boolean,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        require(intervalMinutes >= 15L) { "Android 后台周期任务最短间隔为 15 分钟" }
        val now = System.currentTimeMillis()
        val firstRun = firstRunAtMillis.coerceAtLeast(now)
        store.upsert(
            AutomationTask(
                id = id,
                prompt = prompt,
                createdAt = now,
                nextRunAt = firstRun,
                recurringMinutes = intervalMinutes,
                notify = notify,
            ),
        )
        val request = PeriodicWorkRequestBuilder<HarnessAutomationWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setInitialDelay((firstRun - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_TASK_ID, id).build())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun list(): List<AutomationTask> = store.list()

    fun cancelTask(id: String): Boolean {
        workManager.cancelUniqueWork(workName(id))
        return store.remove(id)
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "任务编号仅允许字母、数字、点、下划线和横线" }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val WORK_TAG = "harness-automation"
        fun workName(id: String) = "harness-automation-$id"
    }
}

class HarnessAutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun localHarnessEngine(): LocalHarnessEngine
        fun automationStore(): AutomationStore
        fun notifications(): DshNotifications
        fun hostsStore(): HostsStore
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(HarnessAutomationScheduler.KEY_TASK_ID)
            ?: return Result.failure()
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        )
        val store = entry.automationStore()
        val task = store.get(id) ?: return Result.success()
        val started = System.currentTimeMillis()
        store.update(id) { it.copy(status = "running", lastRunAt = started, lastError = null) }

        return try {
            val run = entry.localHarnessEngine().runAutomationWork(
                text = task.prompt,
                preferredSessionId = task.workSessionId,
            )
            val next = task.recurringMinutes?.let { System.currentTimeMillis() + it * 60_000L }
                ?: task.nextRunAt
            store.update(id) {
                it.copy(
                    workSessionId = run.sessionId,
                    status = if (it.recurringMinutes == null) "completed" else "scheduled",
                    nextRunAt = next,
                    lastResult = run.output.take(20_000),
                    lastError = null,
                )
            }
            maybeNotify(
                entry = entry,
                task = task,
                titleRes = R.string.tasks_notification_complete,
                sessionId = run.sessionId,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (busy: LocalHarnessBusyException) {
            store.update(id) {
                it.copy(
                    status = "queued",
                    lastError = "前台或其他后台任务正在运行，等待重试",
                )
            }
            Result.retry()
        } catch (blocked: LocalHarnessBlockedException) {
            val sessionId = blocked.sessionId ?: task.workSessionId
            store.update(id) {
                it.copy(
                    workSessionId = sessionId ?: it.workSessionId,
                    status = "blocked",
                    lastError = (blocked.message ?: "需要人工处理").take(4_000),
                )
            }
            maybeNotify(
                entry = entry,
                task = task,
                titleRes = R.string.tasks_notification_blocked,
                sessionId = sessionId,
            )
            Result.success()
        } catch (error: Throwable) {
            store.update(id) {
                it.copy(
                    status = if (it.recurringMinutes == null) "failed" else "scheduled",
                    nextRunAt = it.recurringMinutes?.let { minutes ->
                        System.currentTimeMillis() + minutes * 60_000L
                    } ?: it.nextRunAt,
                    lastError = (error.message ?: error::class.java.simpleName).take(4_000),
                )
            }
            maybeNotify(
                entry = entry,
                task = task,
                titleRes = R.string.tasks_notification_failed,
                sessionId = task.workSessionId,
            )
            Result.success()
        }
    }

    private suspend fun maybeNotify(
        entry: WorkerEntryPoint,
        task: AutomationTask,
        titleRes: Int,
        sessionId: String?,
    ) {
        if (!task.notify) return
        val enabled = runCatching { entry.hostsStore().settingsOnce().notifyLocalJobs }
            .getOrDefault(true)
        if (!enabled) return
        entry.notifications().postLocalSession(
            id = AUTOMATION_NOTIFICATION_BASE + (task.id.hashCode() and Int.MAX_VALUE) % 10_000,
            title = applicationContext.getString(titleRes),
            text = applicationContext.getString(R.string.tasks_notification_open_result),
            sessionId = sessionId,
        )
    }

    companion object {
        private const val AUTOMATION_NOTIFICATION_BASE = 34_000
    }
}

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
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            val now = System.currentTimeMillis()
            val runAt = input["run_at_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: (now + (input["delay_minutes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 60_000L)
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
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            val first = System.currentTimeMillis() +
                (input["delay_minutes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 60_000L
            val id = input.required("id")
            scheduler.schedulePeriodic(
                id = id,
                prompt = input.required("prompt"),
                intervalMinutes = input.required("interval_minutes").toLong(),
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
        access: ToolAccess = ToolAccess.SESSION_WRITE,
        approval: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
        execute: suspend (JsonObject) -> String,
    ) {
        context.tools.register(
            HarnessTool(
                name = name,
                schema = buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name)
                        put("description", description)
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                properties.forEach { (key, type) ->
                                    put(key, buildJsonObject { put("type", type) })
                                }
                            })
                            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                            put("additionalProperties", false)
                        })
                    })
                },
                access = access,
                approvalPolicy = approval,
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

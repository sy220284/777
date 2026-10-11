package com.labteto.dshmobile.local.model

import android.content.Context
import com.labteto.dshmobile.observability.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * This is the single durable fact source for provider-side video creation.
 * SUBMITTING means the external request may already have been accepted. If the process dies
 * before its returned ID is committed, never silently replay a possibly billable request.
 */
@Serializable
enum class LocalMediaVideoPhase { SUBMITTING, OUTCOME_UNKNOWN, QUEUED, PROCESSING, SUCCEEDED, FAILED }

@Serializable
data class LocalMediaVideoTask(
    val id: String,
    val offerId: String,
    val routeFingerprint: String,
    val provider: String,
    val model: String,
    val profileId: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val phase: LocalMediaVideoPhase,
    val providerTaskId: String? = null,
    val downloadUrl: String? = null,
    val lastError: String? = null,
) {
    /** A removed, replaced or re-keyed account must not be used to poll an old task. */
    fun belongsTo(offer: LocalMediaOffer): Boolean =
        offerId == offer.id && routeFingerprint == offer.profile.routeFingerprint() &&
            provider == offer.provider.name && model == offer.model
}

@Serializable
internal data class LocalMediaVideoTaskList(val tasks: List<LocalMediaVideoTask> = emptyList())

@Singleton
class LocalMediaVideoTaskStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences("local_media_video_tasks", Context.MODE_PRIVATE)
    private val mutableTasks = MutableStateFlow(readAndRecover())
    val tasks: StateFlow<List<LocalMediaVideoTask>> = mutableTasks

    @Synchronized
    fun begin(offer: LocalMediaOffer, now: Long = System.currentTimeMillis()): LocalMediaVideoTask {
        LocalMediaCatalog.requireValid(offer)
        require(offer.kind == LocalMediaKind.VIDEO)
        val task = LocalMediaVideoTask(
            id = UUID.randomUUID().toString(),
            offerId = offer.id,
            routeFingerprint = offer.profile.routeFingerprint(),
            provider = offer.provider.name,
            model = offer.model,
            profileId = offer.profile.id,
            startedAtMillis = now,
            updatedAtMillis = now,
            phase = LocalMediaVideoPhase.SUBMITTING,
        )
        persist(mutableTasks.value + task)
        return task
    }

    /** Only the original creator can attach a provider task id to a persisted pending operation. */
    @Synchronized
    fun submitted(id: String, providerTaskId: String) {
        require(providerTaskId.isNotBlank() && providerTaskId.length <= 256)
        transition(id) { previous ->
            check(previous.phase == LocalMediaVideoPhase.SUBMITTING)
            previous.copy(
                phase = LocalMediaVideoPhase.QUEUED,
                providerTaskId = providerTaskId,
                updatedAtMillis = System.currentTimeMillis(),
                lastError = null,
            )
        }
    }

    @Synchronized
    fun unknown(id: String, failure: String) {
        transition(id) { previous ->
            if (previous.phase != LocalMediaVideoPhase.SUBMITTING) previous else previous.copy(
                phase = LocalMediaVideoPhase.OUTCOME_UNKNOWN,
                updatedAtMillis = System.currentTimeMillis(),
                lastError = failure.take(300),
            )
        }
    }

    /** Polling is read-only and never creates a new provider task; preserve existing ID. */
    @Synchronized
    fun checked(id: String, phase: LocalMediaVideoPhase, url: String?) {
        require(phase in setOf(
            LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING,
            LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED,
        ))
        transition(id) { previous ->
            require(!previous.providerTaskId.isNullOrBlank())
            if (previous.phase == LocalMediaVideoPhase.SUCCEEDED || previous.phase == LocalMediaVideoPhase.FAILED) {
                previous
            } else {
                previous.copy(
                    phase = phase,
                    downloadUrl = if (phase == LocalMediaVideoPhase.SUCCEEDED) url else null,
                    lastError = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    @Synchronized
    fun queryFailure(id: String, reason: String) {
        transition(id) { previous ->
            if (previous.phase in setOf(LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED)) previous
            else previous.copy(lastError = reason.take(300), updatedAtMillis = System.currentTimeMillis())
        }
    }

    fun find(id: String): LocalMediaVideoTask? = mutableTasks.value.firstOrNull { it.id == id }

    @Synchronized
    private fun transition(id: String, operation: (LocalMediaVideoTask) -> LocalMediaVideoTask) {
        val previous = mutableTasks.value
        val index = previous.indexOfFirst { it.id == id }
        require(index >= 0) { "未找到对应的视频任务" }
        val next = previous.toMutableList()
        next[index] = operation(previous[index])
        persist(next)
    }

    private fun readAndRecover(): List<LocalMediaVideoTask> {
        val source = prefs.getString(KEY, null) ?: return emptyList()
        val restored = runCatching {
            json.decodeFromString(LocalMediaVideoTaskList.serializer(), source).tasks
        }.onFailure {
            AppLog.warn("MediaTasks", "failure=true operation=read_media_tasks cause_type=${it.javaClass.simpleName}")
        }.getOrElse { return emptyList() }
        val recovered = restored.map { task ->
            if (task.phase == LocalMediaVideoPhase.SUBMITTING) task.copy(
                phase = LocalMediaVideoPhase.OUTCOME_UNKNOWN,
                lastError = "应用退出时视频提交结果尚未确认。请检查服务商后台，勿直接重复生成。",
            ) else task
        }
        if (restored != recovered) {
            check(prefs.edit().putString(KEY,
                json.encodeToString(LocalMediaVideoTaskList.serializer(), LocalMediaVideoTaskList(recovered))
            ).commit()) { "视频任务恢复结果无法持久保存" }
        }
        return recovered
    }

    /** Do not evict unresolved or completed charged tasks automatically. */
    private fun persist(items: List<LocalMediaVideoTask>) {
        val encoded = json.encodeToString(LocalMediaVideoTaskList.serializer(), LocalMediaVideoTaskList(items))
        check(prefs.edit().putString(KEY, encoded).commit()) { "视频任务无法持久保存；已阻止可能付费的请求" }
        mutableTasks.value = items
    }

    private companion object { const val KEY = "video_tasks_v1" }
}

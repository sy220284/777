package com.labteto.dshmobile.local.model

import android.content.Context
import java.io.File
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
    val sessionId: String? = null,
    val description: String? = null,
    val fileName: String? = null,
) {
    /** A removed, replaced or re-keyed account must not be used to poll an old task. */
    fun belongsTo(offer: LocalMediaOffer): Boolean =
        offerId == offer.id && routeFingerprint == offer.profile.routeFingerprint() &&
            provider == offer.provider.name && model == offer.model
}

@Serializable
internal data class LocalMediaVideoTaskList(val tasks: List<LocalMediaVideoTask> = emptyList())

/** Pure state transitions shared by persistence and JVM crash-recovery regression tests. */
internal object LocalMediaVideoTransitions {
    fun recover(tasks: List<LocalMediaVideoTask>): List<LocalMediaVideoTask> =
        tasks.map { task ->
            if (task.phase == LocalMediaVideoPhase.SUBMITTING) task.copy(
                phase = LocalMediaVideoPhase.OUTCOME_UNKNOWN,
                lastError = "应用退出时视频提交结果尚未确认。请检查服务商后台，勿直接重复生成。",
            ) else task
        }

    fun accepted(
        previous: LocalMediaVideoTask,
        providerTaskId: String,
        now: Long,
    ): LocalMediaVideoTask {
        require(providerTaskId.isNotBlank() && providerTaskId.length <= 256)
        check(previous.phase == LocalMediaVideoPhase.SUBMITTING)
        return previous.copy(
            phase = LocalMediaVideoPhase.QUEUED,
            providerTaskId = providerTaskId,
            updatedAtMillis = now,
            lastError = null,
        )
    }

    fun polled(
        previous: LocalMediaVideoTask,
        phase: LocalMediaVideoPhase,
        url: String?,
        now: Long,
    ): LocalMediaVideoTask {
        require(phase in setOf(
            LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING,
            LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED,
        ))
        require(!previous.providerTaskId.isNullOrBlank())
        if (previous.phase in setOf(LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED)) {
            return previous
        }
        if (phase == LocalMediaVideoPhase.SUCCEEDED) {
            require(url != null && url.startsWith("https://")) { "生成已完成但缺少可靠视频地址" }
        }
        return previous.copy(
            phase = phase,
            downloadUrl = if (phase == LocalMediaVideoPhase.SUCCEEDED) url else null,
            lastError = null,
            updatedAtMillis = now,
        )
    }
}

@Singleton
class LocalMediaVideoTaskStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences("local_media_video_tasks", Context.MODE_PRIVATE)
    private val document = LocalMediaDocument(
        File(context.filesDir, "media-studio/video-tasks.json"), LocalMediaVideoTaskList.serializer(), json,
        legacySource = { prefs.getString(KEY, null) },
    )
    val recoveryErrors: StateFlow<String?> get() = document.recoveryErrors
    private val mutableTasks = MutableStateFlow(readAndRecover())
    val tasks: StateFlow<List<LocalMediaVideoTask>> = mutableTasks

    @Synchronized
    fun begin(
        offer: LocalMediaOffer, now: Long = System.currentTimeMillis(),
        sessionId: String? = null, description: String? = null,
    ): LocalMediaVideoTask {
        document.requireAvailable()
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
            sessionId = sessionId?.takeIf(String::isNotBlank),
            description = description?.take(600),
        )
        persist(mutableTasks.value + task)
        return task
    }

    /** Only the original creator can attach a provider task id to a persisted pending operation. */
    @Synchronized
    fun submitted(id: String, providerTaskId: String) {
        transition(id) { previous ->
            LocalMediaVideoTransitions.accepted(previous, providerTaskId, System.currentTimeMillis())
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
        transition(id) { previous ->
            LocalMediaVideoTransitions.polled(previous, phase, url, System.currentTimeMillis())
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

    /** A completed video can be played/shared without depending on an expiring CDN URL. */
    fun fileFor(task: LocalMediaVideoTask): File? {
        val name = task.fileName?.takeIf {
            it.matches(Regex("[A-Za-z0-9._-]{1,180}")) && it != "." && it != ".."
        } ?: return null
        val dir = File(context.filesDir, "media-studio").canonicalFile
        return File(dir, name).takeIf { it.canonicalFile.parentFile == dir && it.isFile }
    }

    @Synchronized
    fun savedFile(id: String, file: File) {
        val dir = File(context.filesDir, "media-studio").canonicalFile
        require(file.isFile && file.canonicalFile.parentFile == dir)
        transition(id) { previous ->
            require(previous.phase == LocalMediaVideoPhase.SUCCEEDED)
            previous.copy(fileName = file.name, lastError = null,
                updatedAtMillis = System.currentTimeMillis())
        }
    }

    @Synchronized
    fun downloadFailure(id: String, reason: String) {
        transition(id) { previous ->
            if (previous.phase != LocalMediaVideoPhase.SUCCEEDED || previous.fileName != null) previous
            else previous.copy(lastError = "本地保存失败：${reason.take(180)}（可暂时在线播放）")
        }
    }

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
        val restored = document.readOrNull(::LocalMediaVideoTaskList)?.tasks ?: return emptyList()
        val recovered = LocalMediaVideoTransitions.recover(restored)
        if (restored != recovered) document.write(LocalMediaVideoTaskList(recovered))
        return recovered
    }

    /** Do not evict unresolved or completed charged tasks automatically. */
    private fun persist(items: List<LocalMediaVideoTask>) {
        document.write(LocalMediaVideoTaskList(items))
        mutableTasks.value = items
    }

    private companion object { const val KEY = "video_tasks_v1" }
}

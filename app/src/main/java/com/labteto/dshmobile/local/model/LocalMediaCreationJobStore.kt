package com.labteto.dshmobile.local.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent submission receipts for billable synchronous media endpoints.
 * On process death an in-flight HTTP request is UNKNOWN, never silently retried.
 * Completed receipts share their identifier with the actual media result.
 */
@Serializable
enum class LocalMediaCreationPhase { SUBMITTING, OUTCOME_UNKNOWN, FAILED, SUCCEEDED }

@Serializable
data class LocalMediaCreationJob(
    val id: String,
    val sessionId: String,
    val kind: LocalMediaKind,
    val provider: String,
    val model: String,
    val prompt: String?,
    val createdAtMillis: Long,
    val phase: LocalMediaCreationPhase = LocalMediaCreationPhase.SUBMITTING,
    val error: String? = null,
)

@Serializable
internal data class LocalMediaCreationJobList(
    val jobs: List<LocalMediaCreationJob> = emptyList(),
)

internal object LocalMediaCreationJobRecovery {
    fun recover(items: List<LocalMediaCreationJob>): List<LocalMediaCreationJob> =
        items.map { item ->
            if (item.phase == LocalMediaCreationPhase.SUBMITTING)
                item.copy(phase = LocalMediaCreationPhase.OUTCOME_UNKNOWN,
                    error = "应用退出时生成结果未确认；请先核对服务商记录，避免重复扣费。")
            else item
        }
}

@Singleton
class LocalMediaCreationJobStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val document = LocalMediaDocument(
        File(context.filesDir, "media-studio/creation-jobs.json"),
        LocalMediaCreationJobList.serializer(), json,
    )
    val recoveryErrors: StateFlow<String?> get() = document.recoveryErrors
    private val mutableJobs = MutableStateFlow(readAndRecover())
    val jobs: StateFlow<List<LocalMediaCreationJob>> = mutableJobs

    @Synchronized
    fun begin(offer: LocalMediaOffer, sessionId: String, prompt: String?): LocalMediaCreationJob {
        document.requireAvailable()
        require(sessionId.isNotBlank()) { "创作需要有效的当前会话" }
        require(offer.kind in setOf(LocalMediaKind.IMAGE, LocalMediaKind.SPEECH, LocalMediaKind.TRANSCRIBE))
        LocalMediaCatalog.requireValid(offer)
        val item = LocalMediaCreationJob(
            id = UUID.randomUUID().toString(), sessionId = sessionId,
            kind = offer.kind, provider = offer.provider.name, model = offer.model,
            prompt = prompt?.take(600), createdAtMillis = System.currentTimeMillis(),
        )
        persist(mutableJobs.value + item)
        return item
    }

    @Synchronized
    fun finish(id: String) = transition(id) { it.copy(phase = LocalMediaCreationPhase.SUCCEEDED, error = null) }

    @Synchronized
    fun uncertain(id: String, message: String) = transition(id) {
        if (it.phase != LocalMediaCreationPhase.SUBMITTING) it
        else it.copy(
            phase = LocalMediaCreationPhase.OUTCOME_UNKNOWN,
            error = message.take(260),
        )
    }

    @Synchronized
    fun dismiss(id: String) {
        document.requireAvailable()
        require(mutableJobs.value.firstOrNull { it.id == id }?.phase != LocalMediaCreationPhase.SUBMITTING) {
            "正在生成，不能删除该任务记录"
        }
        persist(mutableJobs.value.filterNot { it.id == id })
    }

    @Synchronized
    private fun transition(id: String, update: (LocalMediaCreationJob) -> LocalMediaCreationJob) {
        val items = mutableJobs.value
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val next = items.toMutableList()
        next[index] = update(items[index])
        persist(next)
    }

    private fun readAndRecover(): List<LocalMediaCreationJob> {
        val source = document.readOrNull(::LocalMediaCreationJobList)?.jobs.orEmpty()
        val recovered = LocalMediaCreationJobRecovery.recover(source)
        if (source != recovered) document.write(LocalMediaCreationJobList(recovered))
        return recovered
    }

    private fun persist(items: List<LocalMediaCreationJob>) {
        document.write(LocalMediaCreationJobList(items))
        mutableJobs.value = items
    }
}

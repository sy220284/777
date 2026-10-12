package com.labteto.dshmobile.local.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Finished media results, separate from provider-side video job lifecycle. No prompt or secret. */
@Serializable
data class LocalMediaResult(
    val id: String,
    val model: String,
    val provider: String,
    val kind: String,
    val createdAtMillis: Long,
    val fileName: String? = null,
    val remoteUrl: String? = null,
    /** Null for legacy/unassigned results, which stay in the global media archive. */
    val sessionId: String? = null,
    val description: String? = null,
    val textOutput: String? = null,
) {
    init { require(listOf(fileName, remoteUrl, textOutput).count { it != null } == 1) }
}

@Serializable
internal data class LocalMediaResultList(val results: List<LocalMediaResult> = emptyList())

/**
 * Media results are app-private durable files, never disposable network cache.
 * Provider CDN URLs are indexed as *temporary links*, not falsely described as downloaded.
 * Explicit user removal is required; we don't evict paid creations automatically.
 */
@Singleton
class LocalMediaResultStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences("local_media_results", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "media-studio")
    private val document = LocalMediaDocument(
        File(directory, "results.json"), LocalMediaResultList.serializer(), json,
        legacySource = { prefs.getString(KEY, null) },
    )
    val recoveryErrors: StateFlow<String?> get() = document.recoveryErrors
    fun requireAvailable() = document.requireAvailable()
    private val mutableResults = MutableStateFlow(load())
    val results: StateFlow<List<LocalMediaResult>> = mutableResults

    @Synchronized
    fun addFile(
        offer: LocalMediaOffer, kind: LocalMediaKind, file: File,
        sessionId: String? = null, description: String? = null,
    ): LocalMediaResult {
        require(kind in setOf(LocalMediaKind.IMAGE, LocalMediaKind.SPEECH))
        require(file.isFile) { "生成文件不存在" }
        require(file.canonicalFile.parentFile == directory.canonicalFile) {
            "生成结果没有存入受管理的持久目录"
        }
        return append(offer, kind, fileName = file.name, sessionId = sessionId, description = description)
    }

    @Synchronized
    fun addLink(
        offer: LocalMediaOffer, kind: LocalMediaKind, url: String,
        sessionId: String? = null, description: String? = null,
    ): LocalMediaResult {
        require(kind == LocalMediaKind.IMAGE)
        val location = URI(url)
        require(location.scheme.equals("https", true) && location.userInfo == null &&
            !location.host.isNullOrBlank() && location.port == -1) {
            "服务商未返回安全的媒体链接"
        }
        return append(offer, kind, remoteUrl = url, sessionId = sessionId, description = description)
    }

    /** Transcripts are media deliverables, not model-authored dialogue turns. */
    @Synchronized
    fun addText(offer: LocalMediaOffer, sessionId: String?, text: String): LocalMediaResult {
        require(offer.kind == LocalMediaKind.TRANSCRIBE && text.isNotBlank())
        return append(offer, LocalMediaKind.TRANSCRIBE, textOutput = text, sessionId = sessionId)
    }

    @Synchronized
    fun remove(id: String) {
        document.requireAvailable()
        val current = mutableResults.value
        val existing = current.firstOrNull { it.id == id } ?: return
        val file = fileFor(existing)
        if (file != null && file.exists()) {
            check(file.delete()) { "无法删除生成文件，请重试" }
        }
        persist(current.filterNot { it.id == id })
    }

    fun fileFor(item: LocalMediaResult): File? {
        val name = item.fileName?.takeIf {
            it.matches(Regex("[A-Za-z0-9._-]{1,180}")) && it != "." && it != ".."
        } ?: return null
        return File(directory, name).takeIf {
            it.canonicalFile.parentFile == directory.canonicalFile && it.isFile
        }
    }

    private fun append(
        offer: LocalMediaOffer,
        kind: LocalMediaKind,
        fileName: String? = null,
        remoteUrl: String? = null,
        textOutput: String? = null,
        sessionId: String? = null,
        description: String? = null,
    ): LocalMediaResult {
        LocalMediaCatalog.requireValid(offer)
        val record = LocalMediaResult(
            id = UUID.randomUUID().toString(),
            model = offer.model,
            provider = offer.provider.name,
            kind = kind.name,
            createdAtMillis = System.currentTimeMillis(),
            fileName = fileName,
            remoteUrl = remoteUrl,
            sessionId = sessionId?.takeIf(String::isNotBlank),
            description = description?.take(600),
            textOutput = textOutput,
        )
        persist(mutableResults.value + record)
        return record
    }

    @Synchronized
    private fun persist(items: List<LocalMediaResult>) {
        document.write(LocalMediaResultList(items))
        mutableResults.value = items
    }

    private fun load(): List<LocalMediaResult> =
        document.readOrNull(::LocalMediaResultList)?.results.orEmpty()

    private companion object { const val KEY = "completed_media_results_v1" }
}

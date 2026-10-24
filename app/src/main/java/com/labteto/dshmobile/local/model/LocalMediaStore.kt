package com.labteto.dshmobile.local.model

// FEATURE_TREE: {"id":"tools.media.paged-history","path":"第一层｜产品功能与界面 / 工具与扩展功能 / 视觉与媒体工具 / 媒体档案分页与会话隔离","owner":"TOOLS","entry":"page","test":"app/src/androidTest/java/com/labteto/dshmobile/local/model/LocalMediaStoreAndroidTest.kt#indexedPagesKeepAllHistoryAndSeparateSessions"}

// FEATURE_TREE: {"id":"tools.media.transactional-recovery","path":"第一层｜产品功能与界面 / 工具与扩展功能 / 视觉与媒体工具 / 媒体创作回执与成果恢复","owner":"TOOLS","entry":"LocalMediaStore","test":"app/src/androidTest/java/com/labteto/dshmobile/local/model/LocalMediaStoreAndroidTest.kt#receiptAndResultCommitTogetherAndLateWriterCannotResurrectDeletion"}

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Bounded UI page; hasMore means older charged results remain available, not evicted. */
/** Media owns migration, atomic receipt/result state, indexed reads and recoverable deletion. */
@Singleton
class LocalMediaStore @Inject constructor(@ApplicationContext private val context: Context, private val json: Json) {
    private val directory = File(context.filesDir, "media-studio")
    private val database = LocalMediaDatabase(context, json)
    private var initialized = false
    private val changes = MutableStateFlow(0L)
    private val mutableError = MutableStateFlow<String?>(null)
    val recoveryErrors: StateFlow<String?> = mutableError

    @Synchronized
    fun initialize() = operation {
        if (initialized) return@operation
        val migrationPreferences = context.getSharedPreferences("local_media_migration", Context.MODE_PRIVATE)
        check(!migrationPreferences.getBoolean("database_established", false) || context.getDatabasePath("local_media_v1.db").isFile) {
            "媒体数据库缺失，请恢复原数据库，不能把已有付费成果当成空历史"
        }
        if (!database.migrated()) {
            fun <T> source(name: String, serializer: kotlinx.serialization.KSerializer<T>, empty: () -> T, legacy: () -> String? = { null }): T {
                val doc = LocalMediaDocument(File(directory, name), serializer, json, legacy)
                return doc.readOrNull(empty) ?: error(doc.recoveryError ?: "媒体迁移来源无法恢复")
            }
            val jobs = source("creation-jobs.json", LocalMediaCreationJobList.serializer(), ::LocalMediaCreationJobList).jobs
            val results = source("results.json", LocalMediaResultList.serializer(), ::LocalMediaResultList) {
                context.getSharedPreferences("local_media_results", Context.MODE_PRIVATE).getString("completed_media_results_v1", null)
            }.results
            val videos = source("video-tasks.json", LocalMediaVideoTaskList.serializer(), ::LocalMediaVideoTaskList) {
                context.getSharedPreferences("local_media_video_tasks", Context.MODE_PRIVATE).getString("video_tasks_v1", null)
            }.tasks
            require(jobs.map { it.id }.distinct().size == jobs.size && results.map { it.id }.distinct().size == results.size) {
                "媒体迁移出现重复记录身份，原来源保留"
            }
            val records = linkedMapOf<String, LocalMediaRecord>()
            jobs.forEach { records[it.id] = LocalMediaRecord(it.id, job = it) }
            results.forEach { records[it.id] = (records[it.id] ?: LocalMediaRecord(it.id)).copy(result = it) }
            videos.forEach { check(it.id !in records) { "媒体迁移出现重复任务身份" }; records[it.id] = LocalMediaRecord(it.id, video = it) }
            database.migrate(records.values.toList())
        }
        database.transaction {
            database.pending().forEach { row ->
                val recoveredJob = row.job?.let { LocalMediaCreationJobRecovery.recover(listOf(it)).single() }
                val job = if (row.result != null) recoveredJob?.copy(phase = LocalMediaCreationPhase.SUCCEEDED, error = null) else recoveredJob
                val video = row.video?.let { LocalMediaVideoTransitions.recover(listOf(it)).single() }
                    ?.let { if (it.deliveryPhase == LocalMediaDeliveryPhase.SAVING) it.copy(deliveryPhase = LocalMediaDeliveryPhase.PENDING) else it }
                database.put(row.copy(job = job, video = video))
            }
        }
        check(migrationPreferences.edit().putBoolean("database_established", true).commit()) { "媒体迁移标记保存失败，原来源保留" }
        initialized = true
        archiveLegacySources()
    }

    fun observe(sessionId: String? = null, limit: Int = PAGE_SIZE): Flow<LocalMediaPage> = changes.map {
        withContext(Dispatchers.IO) {
            try { page(sessionId, limit) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { LocalMediaPage(unavailable = true) }
        }
    }
    @Synchronized
    fun page(sessionId: String? = null, limit: Int = PAGE_SIZE): LocalMediaPage = operation {
        initialize()
        val rows = database.page(sessionId, Math.addExact(limit, 1))
        val selected = rows.take(limit).asReversed()
        LocalMediaPage(selected.mapNotNull { it.result }, selected.mapNotNull { it.job }, selected.mapNotNull { it.video }, rows.size > limit)
    }
    @Synchronized
    fun beginCreation(offer: LocalMediaOffer, sessionId: String, prompt: String?, sourceMessageId: String? = null, revisionOf: String? = null, credentialSignature: String? = null): LocalMediaCreationJob = operation {
        initialize(); LocalMediaCatalog.requireValid(offer); require(sessionId.isNotBlank())
        val job = LocalMediaCreationJob(UUID.randomUUID().toString(), sessionId, offer.kind.name, offer.provider.name, offer.model,
            prompt, System.currentTimeMillis(), LocalMediaCreationPhase.PREPARING,
            profileId = offer.profile.id, routeFingerprint = offer.profile.routeFingerprint(), sourceMessageId = sourceMessageId,
            revisionOf = revisionOf, credentialSignature = credentialSignature)
        commit(LocalMediaRecord(job.id, job = job)); job
    }
    @Synchronized
    fun requestStarted(id: String) = update(id) { row ->
        val job = row.job ?: error("创作回执不存在")
        if (job.phase == LocalMediaCreationPhase.PREPARING) row.copy(job = job.copy(phase = LocalMediaCreationPhase.SUBMITTING)) else row
    }
    @Synchronized
    fun providerSucceeded(id: String) = update(id) { row ->
        val job = row.job ?: error("创作回执不存在")
        row.copy(job = job.copy(phase = LocalMediaCreationPhase.SAVING))
    }
    @Synchronized
    fun creationFailure(id: String, failure: Exception) = update(id) { row ->
        val job = row.job ?: error("创作回执不存在")
        if (row.result != null) return@update row
        val notSent = job.phase == LocalMediaCreationPhase.PREPARING
        row.copy(job = job.copy(
            phase = if (notSent || failure is LocalMediaRequestRejected) LocalMediaCreationPhase.FAILED else
                if (failure is LocalMediaSaveFailure || job.phase == LocalMediaCreationPhase.SAVING) LocalMediaCreationPhase.SAVE_FAILED else LocalMediaCreationPhase.OUTCOME_UNKNOWN,
            errorCode = if (notSent) "NOT_SENT" else if (failure is LocalMediaRequestRejected) "REQUEST_REJECTED" else
                if (failure is LocalMediaSaveFailure || job.phase == LocalMediaCreationPhase.SAVING) "LOCAL_SAVE_FAILED" else "OUTCOME_UNKNOWN",
            error = failure.message?.take(400),
        ))
    }
    @Synchronized
    fun addResult(offer: LocalMediaOffer, kind: LocalMediaKind, sessionId: String?, description: String? = null,
        operationId: String? = null, file: File? = null, link: String? = null, text: String? = null,
        sourceMessageId: String? = null, imageRatio: String? = null, voiceId: String? = null, revisionOf: String? = null): LocalMediaResult = operation {
        initialize(); LocalMediaCatalog.requireValid(offer)
        file?.let { require(it.isFile && it.canonicalFile.parentFile == directory.canonicalFile) }
        link?.let { val uri = URI(it); require(uri.scheme.equals("https", true) && uri.userInfo == null && !uri.host.isNullOrBlank() && uri.port == -1) }
        val result = LocalMediaResult(operationId ?: UUID.randomUUID().toString(), offer.model, offer.provider.name, kind.name,
            System.currentTimeMillis(), fileName = file?.name, remoteUrl = link, sessionId = sessionId, description = description, textOutput = text, sourceMessageId = sourceMessageId,
            profileId = offer.profile.id, routeFingerprint = offer.profile.routeFingerprint(),
            imageRatio = imageRatio, voiceId = voiceId, revisionOf = revisionOf,
            credentialSignature = operationId?.let { database.get(it)?.job?.credentialSignature })
        database.transaction {
            val previous = if (operationId != null) database.get(operationId)?.takeUnless { it.deleted } ?: error("创作回执已失效") else LocalMediaRecord(result.id)
            val job = previous.job?.copy(phase = LocalMediaCreationPhase.SUCCEEDED, error = null, errorCode = null)
            database.put(previous.copy(job = job, result = result))
        }
        changed(); result
    }
    @Synchronized
    fun beginVideo(offer: LocalMediaOffer, sessionId: String?, prompt: String, sourceMessageId: String? = null, revisionOf: String? = null, credentialSignature: String? = null): LocalMediaVideoTask = operation {
        initialize(); LocalMediaCatalog.requireValid(offer)
        val now = System.currentTimeMillis()
        val task = LocalMediaVideoTask(UUID.randomUUID().toString(), offer.id, offer.profile.routeFingerprint(), offer.provider.name,
            offer.model, offer.profile.id, now, now, LocalMediaVideoPhase.SUBMITTING, sessionId = sessionId, description = prompt, sourceMessageId = sourceMessageId, revisionOf = revisionOf, credentialSignature = credentialSignature)
        commit(LocalMediaRecord(task.id, video = task)); task
    }
    @Synchronized
    fun video(id: String): LocalMediaVideoTask? = operation { initialize(); database.get(id)?.takeUnless { it.deleted }?.video }
    @Synchronized
    fun updateVideo(id: String, transition: (LocalMediaVideoTask) -> LocalMediaVideoTask) = update(id) { row ->
        row.copy(video = transition(row.video ?: error("视频任务不存在")))
    }
    /** Saved local media results are retrieved by the single result() lookup below. */
    @Synchronized
    fun videosNeedingDelivery(): List<LocalMediaVideoTask> = operation {
        initialize(); database.pending().mapNotNull { it.video }.filter { it.phase == LocalMediaVideoPhase.SUCCEEDED && it.fileName == null }
    }
    @Synchronized
    fun result(id: String): LocalMediaResult? = operation {
        initialize(); database.get(id)?.takeUnless { it.deleted }?.result
    }
    /** Atomically attach a downloaded original image, rejecting deletion or changed provenance. */
    @Synchronized
    fun attachDownloadedImage(id: String, sessionId: String, expectedUrl: String, file: File): Boolean = operation {
        initialize()
        require(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) {
            "下载图片不在媒体存储目录"
        }
        var attached = false
        database.transaction {
            val row = database.get(id)?.takeUnless { it.deleted } ?: return@transaction
            val original = row.result ?: return@transaction
            if (original.kind != LocalMediaKind.IMAGE.name || original.sessionId != sessionId ||
                original.remoteUrl != expectedUrl || this@LocalMediaStore.file(original.fileName) != null)
                return@transaction
            database.put(row.copy(result = original.copy(fileName = file.name, remoteUrl = null)))
            attached = true
        }
        if (attached) changed()
        attached
    }

    fun file(name: String?): File? = managedFile(name)?.takeIf(File::isFile)
    private fun managedFile(name: String?): File? = name?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,180}")) && it !in setOf(".", "..") }
        ?.let { File(directory, it).takeIf { f -> f.canonicalFile.parentFile == directory.canonicalFile } }

    @Synchronized
    fun remove(id: String) = operation {
        initialize()
        database.transaction {
            val row = database.get(id) ?: return@transaction
            require(row.job?.phase !in setOf(LocalMediaCreationPhase.PREPARING, LocalMediaCreationPhase.SUBMITTING, LocalMediaCreationPhase.SAVING)) { "创作尚未结束，不能删除回执" }
            require(row.video?.phase !in setOf(LocalMediaVideoPhase.SUBMITTING, LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)) { "视频仍在生成，不能删除任务" }
            database.put(row.copy(deleted = true))
        }
        changed() // Logical removal of receipt + result is one commit, before any file deletion.
        cleanupDeleted()
    }
    /** Remove a visible result and all same-kind revisions so old audio cannot reappear. */
    @Synchronized
    fun removeRevisionFamily(id: String) = operation {
        initialize()
        val target = database.get(id)?.takeUnless { it.deleted } ?: return@operation
        val sessionId = target.sessionId ?: return@operation
        val rows = database.activeForSession(sessionId)
        val byId = rows.associateBy { it.id }
        fun kind(row: LocalMediaRecord): String? = row.result?.kind ?: row.video?.let { "VIDEO" } ?: row.job?.kind
        fun parent(row: LocalMediaRecord): String? =
            row.result?.revisionOf ?: row.video?.revisionOf ?: row.job?.revisionOf
        val mediaKind = kind(target)
        fun root(row: LocalMediaRecord): String {
            var current = row
            val seen = mutableSetOf(row.id)
            repeat(100) {
                val prior = parent(current)?.let(byId::get) ?: return current.id
                if (kind(prior) != mediaKind || !seen.add(prior.id)) return current.id
                current = prior
            }
            return current.id
        }
        val rootId = root(target)
        val family = rows.filter { kind(it) == mediaKind && root(it) == rootId }
        require(family.none { row ->
            row.job?.phase in setOf(LocalMediaCreationPhase.PREPARING, LocalMediaCreationPhase.SUBMITTING, LocalMediaCreationPhase.SAVING) ||
                row.video?.phase in setOf(LocalMediaVideoPhase.SUBMITTING, LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)
        }) { "同一成果仍在生成中，请等待完成后删除" }
        database.transaction { family.forEach { database.put(it.copy(deleted = true)) } }
        changed()
        cleanupDeleted()
    }
    @Synchronized
    fun cleanupDeleted() = operation {
        initialize()
        database.deletions().forEach { row ->
            // Keep the reference check and physical cleanup behind SQLite's write lock.
            // Otherwise a concurrent record can begin referencing the same filename
            // after the check but before the delete, losing a surviving paid result.
            database.transaction {
                val deleted = database.get(row.id)?.takeIf { it.deleted } ?: return@transaction
                val file = managedFile(deleted.fileName)
                if (file != null && !database.hasActiveFileReference(file.name) &&
                    file.exists() && !file.delete()
                ) {
                    throw java.io.IOException("媒体已移出列表，本地文件尚未清理，请重试")
                }
                database.remove(deleted.id)
            }
        }
    }
    @Synchronized
    fun retryStorage() = operation { initialize(); archiveLegacySources(); cleanupDeleted(); page(); mutableError.value = null; changed() }
    /** Migration sources stay recoverable and are never consulted by the normal production path. */
    private fun archiveLegacySources() {
        val archive = File(directory, "migration-archive")
        val names = setOf("creation-jobs.json", "results.json", "video-tasks.json")
        val sources = directory.listFiles().orEmpty().filter { file -> names.any { file.name == it || file.name.startsWith("$it.") } }
        if (sources.isEmpty()) return
        if (!archive.isDirectory && !archive.mkdirs()) throw java.io.IOException("媒体迁移归档目录不可用")
        for (source in sources) {
            val destination = File(archive, source.name)
            if (destination.exists()) throw java.io.IOException("媒体迁移归档已存在，请核对保留的来源")
            if (!source.renameTo(destination)) throw java.io.IOException("旧媒体来源归档未完成；原文件保留")
        }
    }
    private fun update(id: String, transform: (LocalMediaRecord) -> LocalMediaRecord) = operation {
        initialize()
        database.transaction {
            val row = database.get(id)?.takeUnless { it.deleted } ?: error("媒体任务不存在或已删除")
            val next = transform(row)
            if (next != row) database.put(next)
        }
        changed()
    }
    private fun commit(row: LocalMediaRecord) { database.put(row); changed() }
    private fun changed() { changes.value += 1L }
    private inline fun <T> operation(block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        if (!initialized || failure is android.database.sqlite.SQLiteException || failure is java.io.IOException) {
            mutableError.value = failure.message ?: "媒体持久化失败，请恢复存储后重试"
        }
        throw failure
    }
    companion object { const val PAGE_SIZE = 50 }
}

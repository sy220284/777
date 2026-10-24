package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.attachment.cleanupLocalImageAttachments
import com.labteto.dshmobile.local.attachment.collectLocalImageAttachmentReferences
import com.labteto.dshmobile.local.attachment.collectAuthoritativeLocalImageAttachmentReferences
import com.labteto.dshmobile.local.runtime.ATTACHMENT_GC_INTERVAL_MILLIS
import com.labteto.dshmobile.local.runtime.KEY_ATTACHMENT_GC_AT
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeBootstrapPort
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.prepareLocalHarnessStartup
import com.labteto.dshmobile.local.session.LocalSessionDeletionCleanup
import com.labteto.dshmobile.local.session.LocalPendingSessionRegistry
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.chat.LocalChatStyleGuardSettingsPort
import com.labteto.dshmobile.local.work.LocalWorkComposition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** App-level construction root for Runtime startup contributors. */
@Singleton
internal class LocalRuntimeBootstrapComposition @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageTracker: DeepSeekUsageTracker,
    private val bundledRuntimeManager: LocalBundledRuntimeManager,
    private val json: Json,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val pendingSessions: LocalPendingSessionRegistry,
    private val tools: LocalToolCompositionRoot,
    private val skillManager: com.labteto.dshmobile.local.tools.LocalSkillManager,
    private val work: LocalWorkComposition,
    private val foregroundWake: LocalForegroundTurnWakeCoordinator,
    private val foregroundSessionLoader: LocalForegroundSessionLoader,
    private val chatStyleGuardSettings: LocalChatStyleGuardSettingsPort,
    private val chatMemoryProjection: com.labteto.dshmobile.local.chat.LocalChatMemoryProjectionPort,
) : LocalRuntimeBootstrapPort {
    private val root = File(context.filesDir, "local-harness").apply { mkdirs() }
    private val sessionsRoot = File(root, "sessions").apply { mkdirs() }
    private val preferences = LocalHarnessPreferences.from(context)
    private val workspace get() = sessionStorage.files.workspace

    override fun initialize(scope: CoroutineScope, initialSessionId: String) {
        val chatGuard = chatStyleGuardSettings.initialSettings()
        runtimeStateStore.initialize(
            LocalHarnessState(
                workspacePath = workspace.path,
                sessionId = initialSessionId,
                usage = usageTracker.state.value,
                chat = com.labteto.dshmobile.local.chat.LocalChatState(
                    chatStyleGuardEnabled = chatGuard.enabled,
                    chatStyleGuardCustomPhrases = chatGuard.customPhrases,
                ),
            ),
        )
        scope.launch {
            usageTracker.state.collect { usage ->
                runtimeStateStore.projection.update { it.copy(usage = usage) }
            }
        }
        // Do not block Application startup with legacy SQLite migration. Foreground usage
        // writes perform the same idempotent adoption synchronously before their first append.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { usageTracker.reconcileLegacyUsage() }.onFailure {
                com.labteto.dshmobile.observability.AppLog.error(
                    "UsageMigration", "legacy usage reconciliation failed", it,
                )
            }
        }
    }

    override suspend fun prepareAndRestore(scope: CoroutineScope, initialSessionId: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { skillManager.initializeDefaults() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { chatMemoryProjection.recoverPendingDiaryInvalidations() }.onFailure {
                com.labteto.dshmobile.observability.AppLog.warn("MemoryDiaryRecovery", "关联日记清理待恢复：${it.message}")
            }
        }
        // Resolve durable deletion intents BEFORE any foreground Chat/Work restore.
        // A deleted snapshot's leftover events must never be repaired or admitted as
        // a new active conversation on the same startup.
        val deletionFailures = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LocalSessionDeletionCleanup(
                sessionsRoot,
                sessionStorage.eventLogs::clearAndEvict,
                tools.toolOutputStore::deleteSession,
                sessionStorage.coordinator::hasDurableSnapshot,
            ).recoverPending()
        }
        deletionFailures.forEach { (id, failure) ->
            com.labteto.dshmobile.observability.AppLog.warn(
                "SessionDeletion", "会话关联数据清理待重试 session=$id", failure,
            )
        }
        check(deletionFailures.none { it.first == initialSessionId }) {
            "当前会话仍有未完成的删除清理；已停止恢复以避免重放已删除事件"
        }
        if (sessionStorage.coordinator.read(initialSessionId) == null &&
            pendingSessions.modeFor(initialSessionId) == null) {
            pendingSessions.remember(initialSessionId, runtimeStateStore.state.value.usageMode)
        }
        prepareLocalHarnessStartup(
            prepareRuntime = bundledRuntimeManager::prepare,
            installPlugins = { tools.plugins.installStartup() },
            restoreSession = { foregroundSessionLoader.loadStartup(deferReady = true) },
        )
        runtimeStateStore.projection.update { current ->
            if (current.loading) current.copy(loading = false) else current
        }
        foregroundWake.startNextIfIdle()?.start()
        work.schedulePersistentRecovery()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { cleanupLocalImagesIfDue() }
    }

    private fun cleanupLocalImagesIfDue() {
        val now = System.currentTimeMillis()
        val last = preferences.getLong(KEY_ATTACHMENT_GC_AT, 0L)
        if (now - last < ATTACHMENT_GC_INTERVAL_MILLIS) return
        val currentSessionId = runtimeStateStore.currentSessionId
        runCatching {
            // A partially migrated/missing authority is a hard stop for destructive GC.
            // The legacy JSONL directory cannot enumerate SQLite-owned sessions.
            check(sessionStorage.eventLogs.sqliteDatabase != null) {
                "SQLite 会话事件权威来源不可用，附件回收已停止"
            }
            val ids = buildSet {
                addAll(sessionStorage.eventLogs.historicalSources().map { it.id })
                addAll(sessionStorage.coordinator.summaries().map { it.id })
                addAll(pendingSessions.entries().keys)
                add(currentSessionId)
            }
            val refs = collectAuthoritativeLocalImageAttachmentReferences(
                sessionIds = ids,
                currentSessionId = currentSessionId,
                readEventReferences = { id ->
                    // Scoped reader does not evict any foreground EventLog adapter.
                    sessionStorage.eventLogs.readHistorical(id) { log ->
                        log.withEvents { events ->
                            collectLocalImageAttachmentReferences(events)
                        }
                    }
                },
                readSnapshot = { id ->
                    sessionStorage.coordinator.read(id)?.let { snapshot ->
                        json.encodeToJsonElement(LocalHarnessSession.serializer(), snapshot).jsonObject
                    }
                },
                foregroundMessages = runtimeStateStore.foregroundRunHandle.modelHistory.snapshot(),
            )
            val result = cleanupLocalImageAttachments(
                workspaceRoot = File(workspace.path),
                references = refs,
            )
            if (result.deletedFiles > 0) {
                sessionStorage.eventLogs.get(currentSessionId).append("attachment/gc", buildJsonObject {
                    put("status", "completed")
                    put("deleted_files", result.deletedFiles)
                    put("deleted_bytes", result.deletedBytes)
                    put("retained_image_bytes", result.retainedImageBytes)
                })
            }
        }.onSuccess {
            preferences.edit().putLong(KEY_ATTACHMENT_GC_AT, now).apply()
        }.onFailure { failure ->
            // Do not stamp the GC time: retry after recovery instead of silently
            // deleting files whose SQLite owner could not be enumerated/read.
            com.labteto.dshmobile.observability.AppLog.warn(
                "AttachmentGC", "SQLite 引用扫描不完整；本轮没有完成附件清理", failure,
            )
        }
    }
}

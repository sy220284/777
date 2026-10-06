package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.attachment.cleanupLocalImageAttachments
import com.labteto.dshmobile.local.attachment.collectLocalImageAttachmentReferences
import com.labteto.dshmobile.local.attachment.mergeLocalImageAttachmentReferences
import com.labteto.dshmobile.local.runtime.ATTACHMENT_GC_INTERVAL_MILLIS
import com.labteto.dshmobile.local.runtime.KEY_ATTACHMENT_GC_AT
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeBootstrapPort
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.prepareLocalHarnessStartup
import com.labteto.dshmobile.local.session.LocalSessionArchiveMaintenance
import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
import kotlinx.serialization.json.JsonObject
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
    private val tools: LocalToolCompositionRoot,
    private val work: LocalWorkComposition,
    private val foregroundWake: LocalForegroundTurnWakeCoordinator,
    private val foregroundSessionLoader: LocalForegroundSessionLoader,
    private val chatStyleGuardSettings: LocalChatStyleGuardSettingsPort,
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
    }

    override suspend fun prepareAndRestore(scope: CoroutineScope, initialSessionId: String) {
        seedLocalWorkspaceGuide(workspace.path)
        migrateLegacySessionFiles(root, sessionsRoot, initialSessionId)
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
        scope.launch { cleanupLocalImagesIfDue() }
        scope.launch {
            LocalSessionArchiveMaintenance(sessionsRoot, json, runtimeStateStore::currentSessionId).run()
        }
    }

    private fun cleanupLocalImagesIfDue() {
        val now = System.currentTimeMillis()
        val last = preferences.getLong(KEY_ATTACHMENT_GC_AT, 0L)
        if (now - last < ATTACHMENT_GC_INTERVAL_MILLIS) return
        val currentSessionId = runtimeStateStore.currentSessionId
        val eventLog = sessionStorage.eventLogs.get(currentSessionId)
        runCatching {
            cleanupUnreferencedLocalImagesNow(
                sessionsRoot = sessionsRoot,
                currentSessionId = currentSessionId,
                eventLogFor = sessionStorage.eventLogs::get,
                modelHistory = runtimeStateStore.foregroundRunHandle.modelHistory.snapshot(),
                workspacePath = workspace.path,
                eventLog = eventLog,
            )
        }.onSuccess {
            preferences.edit().putLong(KEY_ATTACHMENT_GC_AT, now).apply()
        }
    }
}

private fun migrateLegacySessionFiles(
    root: File,
    sessionsRoot: File,
    currentSessionId: String,
) {
    val legacy = File(root, "session.json")
    val destination = File(sessionsRoot, "$currentSessionId.json")
    if (!legacy.isFile || destination.exists()) return
    legacy.copyTo(destination, overwrite = false)
    File(root, "session.events.jsonl").takeIf(File::isFile)
        ?.copyTo(File(sessionsRoot, "$currentSessionId.events.jsonl"), overwrite = false)
}

private fun seedLocalWorkspaceGuide(workspacePath: String) {
    val skill = File(workspacePath, ".dsh/skills/workspace-guide/SKILL.md")
    if (skill.exists()) return
    skill.parentFile?.mkdirs()
    skill.writeText(
        """
        # 工作区指南

        - 文件操作限当前工作区。
        - 修改前读取，修改后复核。
        - shell 使用 `/system/bin/sh` 和现有命令。
        """.trimIndent() + "\n",
    )
}

private fun cleanupUnreferencedLocalImagesNow(
    sessionsRoot: File,
    currentSessionId: String,
    eventLogFor: (String) -> LocalSessionEventLog,
    modelHistory: List<JsonObject>,
    workspacePath: String,
    eventLog: LocalSessionEventLog,
) {
    val sessionIds = sessionsRoot.listFiles().orEmpty()
        .asSequence()
        .filter(File::isFile)
        .map(File::getName)
        .filter { name -> ".events.jsonl" in name }
        .map { name -> name.substringBefore(".events.jsonl") }
        .filter { id -> id.matches(Regex("[A-Za-z0-9._-]{1,128}")) }
        .plus(currentSessionId)
        .distinct()
        .toList()
    val references = mergeLocalImageAttachmentReferences(
        sessionIds.map { id ->
            eventLogFor(id).withEvents { events ->
                collectLocalImageAttachmentReferences(
                    events = events,
                    extraMessages = if (id == currentSessionId) modelHistory else emptyList(),
                )
            }
        },
    )
    val result = cleanupLocalImageAttachments(
        workspaceRoot = File(workspacePath),
        references = references,
    )
    if (result.deletedFiles > 0) {
        eventLog.append("attachment/gc", buildJsonObject {
            put("status", "completed")
            put("deleted_files", result.deletedFiles)
            put("deleted_bytes", result.deletedBytes)
            put("retained_image_bytes", result.retainedImageBytes)
        })
    }
}

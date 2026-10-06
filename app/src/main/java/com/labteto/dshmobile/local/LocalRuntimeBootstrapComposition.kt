package com.labteto.dshmobile.local

import android.content.Context
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.runtime.ATTACHMENT_GC_INTERVAL_MILLIS
import com.labteto.dshmobile.local.runtime.KEY_ATTACHMENT_GC_AT
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeBootstrapPort
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.prepareLocalHarnessStartup
import com.labteto.dshmobile.local.session.LocalSessionArchiveMaintenance
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
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
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

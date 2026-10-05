package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.automation.LocalAutomationChatCoordinator
import com.labteto.dshmobile.local.automation.LocalAutomationWorkCoordinator
import com.labteto.dshmobile.local.chat.LocalChatExecutionCoordinator
import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatTurnPort
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsPort
import com.labteto.dshmobile.local.session.LocalActiveSessionScopeProvider
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.session.LocalSessionLifecyclePort
import com.labteto.dshmobile.local.tools.LocalToolsManagementPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionCoordinator
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkTurnPort
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Stage-3 app composition root for Feature execution contracts.
 *
 * Engine remains an implementation source only while Work/Chat turn ownership is being migrated.
 * Feature runtimes depend on their own ports and never import Engine directly.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LocalFeatureExecutionPortModule {
    @Provides
    @Singleton
    fun provideLocalAutomationChatCoordinator(
        engine: LocalHarnessEngine,
    ): LocalAutomationChatCoordinator = engine.automationChatCoordinator

    @Provides
    @Singleton
    fun provideLocalAutomationWorkCoordinator(
        engine: LocalHarnessEngine,
    ): LocalAutomationWorkCoordinator = engine.automationWorkCoordinator

    @Provides
    @Singleton
    fun provideLocalWorkExecutionPort(
        coordinator: LocalWorkExecutionCoordinator,
    ): LocalWorkExecutionPort = coordinator

    @Provides
    @Singleton
    fun provideLocalWorkTurnPort(engine: LocalHarnessEngine): LocalWorkTurnPort =
        engine.workTurnPort

    @Provides
    @Singleton
    fun provideLocalChatExecutionPort(
        coordinator: LocalChatExecutionCoordinator,
    ): LocalChatExecutionPort = coordinator

    @Provides
    @Singleton
    fun provideLocalChatTurnPort(engine: LocalHarnessEngine): LocalChatTurnPort =
        engine.chatTurnPort

    @Provides
    @Singleton
    fun provideLocalSessionLifecyclePort(engine: LocalHarnessEngine): LocalSessionLifecyclePort =
        engine.sessionLifecyclePort

    @Provides
    @Singleton
    fun provideLocalToolsManagementPort(engine: LocalHarnessEngine): LocalToolsManagementPort =
        engine.toolsManagementPort

    @Provides
    @Singleton
    fun provideLocalDiagnosticsPort(engine: LocalHarnessEngine): LocalDiagnosticsPort =
        engine.diagnosticsPort

    @Provides
    @Singleton
    fun provideLocalActiveSessionScopeProvider(
        workRunRegistry: LocalWorkRunRegistry,
    ): LocalActiveSessionScopeProvider = LocalActiveSessionScopeProvider { sessionId ->
        workRunRegistry.state(sessionId)?.let { active ->
            LocalSessionAccessScope(
                projectId = active.projectId,
                lineageId = active.lineageId.ifBlank { active.sessionId },
            )
        }
    }
}

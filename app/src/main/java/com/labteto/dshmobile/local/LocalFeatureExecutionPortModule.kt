package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.automation.LocalAutomationChatUserActivityAdapter
import com.labteto.dshmobile.local.chat.LocalChatAutomationExecutionAdapter
import com.labteto.dshmobile.local.chat.LocalChatAutomationExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatExecutionCoordinator
import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatTurnPort
import com.labteto.dshmobile.local.chat.LocalChatTurnStarter
import com.labteto.dshmobile.local.chat.LocalChatUserActivityPort
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsPort
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeBootstrapPort
import com.labteto.dshmobile.local.runtime.LocalWorkDiagnosticsProvider
import com.labteto.dshmobile.local.session.LocalActiveSessionScopeProvider
import com.labteto.dshmobile.local.session.LocalCurrentSessionSnapshotProvider
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.session.LocalSessionLifecyclePort
import com.labteto.dshmobile.local.tools.LocalToolsManagementPort
import com.labteto.dshmobile.local.work.LocalWorkAutomationExecutionAdapter
import com.labteto.dshmobile.local.work.LocalWorkAgentUiPort
import com.labteto.dshmobile.local.work.LocalWorkAutomationExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionCoordinator
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkComposition
import com.labteto.dshmobile.local.work.LocalWorkDiagnosticsAdapter
import com.labteto.dshmobile.local.work.LocalWorkTurnPort
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App composition root for cross-boundary Feature/Runtime contracts.
 *
 * Concrete Feature owners terminate here; Shared Runtime consumes only narrow neutral contracts.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LocalFeatureExecutionPortModule {
    @Provides
    @Singleton
    fun provideLocalRuntimeBootstrapPort(
        composition: LocalRuntimeBootstrapComposition,
    ): LocalRuntimeBootstrapPort = composition

    @Provides
    @Singleton
    fun provideLocalChatUserActivityPort(
        adapter: LocalAutomationChatUserActivityAdapter,
    ): LocalChatUserActivityPort = adapter

    @Provides
    @Singleton
    fun provideLocalChatAutomationExecutionPort(
        adapter: LocalChatAutomationExecutionAdapter,
    ): LocalChatAutomationExecutionPort = adapter

    @Provides
    @Singleton
    fun provideLocalWorkAutomationExecutionPort(
        adapter: LocalWorkAutomationExecutionAdapter,
    ): LocalWorkAutomationExecutionPort = adapter

    @Provides
    @Singleton
    fun provideLocalWorkExecutionPort(
        coordinator: LocalWorkExecutionCoordinator,
    ): LocalWorkExecutionPort = coordinator

    @Provides
    @Singleton
    fun provideLocalWorkAgentUiPort(composition: LocalWorkComposition): LocalWorkAgentUiPort =
        composition

    @Provides
    @Singleton
    fun provideLocalWorkTurnPort(composition: LocalWorkComposition): LocalWorkTurnPort =
        composition.turnStarter

    @Provides
    @Singleton
    fun provideLocalChatExecutionPort(
        coordinator: LocalChatExecutionCoordinator,
    ): LocalChatExecutionPort = coordinator

    @Provides
    @Singleton
    fun provideLocalChatTurnPort(starter: LocalChatTurnStarter): LocalChatTurnPort =
        starter

    @Provides
    @Singleton
    fun provideLocalSessionLifecyclePort(
        composition: LocalSessionComposition,
    ): LocalSessionLifecyclePort = composition

    @Provides
    @Singleton
    fun provideLocalCurrentSessionSnapshotProvider(
        composition: LocalCurrentSessionSnapshotComposition,
    ): LocalCurrentSessionSnapshotProvider = composition

    @Provides
    @Singleton
    fun provideLocalToolsManagementPort(root: LocalToolCompositionRoot): LocalToolsManagementPort =
        root

    @Provides
    @Singleton
    fun provideLocalDiagnosticsPort(runtime: LocalDiagnosticsRuntime): LocalDiagnosticsPort =
        runtime

    @Provides
    @Singleton
    fun provideLocalWorkDiagnosticsProvider(
        adapter: LocalWorkDiagnosticsAdapter,
    ): LocalWorkDiagnosticsProvider = adapter

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

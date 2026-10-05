package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.session.LocalActiveSessionScopeProvider
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.session.LocalSessionLifecyclePort
import com.labteto.dshmobile.local.tools.LocalToolsManagementPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
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
    fun provideLocalWorkExecutionPort(engine: LocalHarnessEngine): LocalWorkExecutionPort =
        engine.workExecutionPort

    @Provides
    @Singleton
    fun provideLocalChatExecutionPort(engine: LocalHarnessEngine): LocalChatExecutionPort =
        engine.chatExecutionPort

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

package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
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
}

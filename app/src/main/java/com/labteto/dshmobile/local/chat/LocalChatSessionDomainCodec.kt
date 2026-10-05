package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionDomainCodec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** ChatFeature interpretation/migration for Chat fields stored in the durable Session envelope. */
internal object LocalChatSessionDomainCodec : LocalSessionDomainCodec {
    override val id: String = "chat"

    override fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession =
        session.copy(
            chatState = session.chatState
                .canonicalizeLegacyCharacterState()
                .withoutLegacyConversationContext(),
            chatContext = session.chatContext.withLegacyFallback(session.chatState),
            chatBranches = session.chatBranches.canonicalizeLegacyChatBranchState(),
            groupChat = session.groupChat.migrateLegacyConversationContext(),
        )
}

/** Composition contribution only; Shared Runtime sees the Session contract, never Chat internals. */
@Module
@InstallIn(SingletonComponent::class)
internal object LocalChatSessionDomainCodecModule {
    @Provides
    @IntoSet
    fun provideLocalChatSessionDomainCodec(): LocalSessionDomainCodec =
        LocalChatSessionDomainCodec
}

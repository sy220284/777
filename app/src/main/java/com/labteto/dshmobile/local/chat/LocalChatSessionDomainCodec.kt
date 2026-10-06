package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionDomainCodec
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.decodeLocalSessionDomain
import com.labteto.dshmobile.local.session.encodeLocalSessionDomain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Chat-owned typed view of the opaque Chat fields persisted inside the Shared Session envelope. */
internal data class LocalChatSessionDomain(
    val chatState: ChatCharacterState = ChatCharacterState(),
    val chatContext: ChatContextState = ChatContextState(),
    val replySuggestions: List<ChatReplySuggestion> = emptyList(),
    val chatBranches: LocalChatBranchState = LocalChatBranchState(),
    val groupChat: LocalGroupChatState = LocalGroupChatState(),
)

internal fun LocalHarnessSession.decodeChatSessionDomain(): LocalChatSessionDomain =
    LocalChatSessionDomain(
        chatState = decodeLocalSessionDomain(chatStatePayload),
        chatContext = decodeLocalSessionDomain(chatContextPayload),
        replySuggestions = decodeLocalSessionDomain(replySuggestionsPayload),
        chatBranches = decodeLocalSessionDomain(chatBranchesPayload),
        groupChat = decodeLocalSessionDomain(groupChatPayload),
    )

internal val LocalHarnessSession.chatState: ChatCharacterState
    get() = decodeLocalSessionDomain(chatStatePayload)

internal val LocalHarnessSession.chatContext: ChatContextState
    get() = decodeLocalSessionDomain(chatContextPayload)

internal val LocalHarnessSession.replySuggestions: List<ChatReplySuggestion>
    get() = decodeLocalSessionDomain(replySuggestionsPayload)

internal val LocalHarnessSession.chatBranches: LocalChatBranchState
    get() = decodeLocalSessionDomain(chatBranchesPayload)

internal val LocalHarnessSession.groupChat: LocalGroupChatState
    get() = decodeLocalSessionDomain(groupChatPayload)

internal fun LocalHarnessSession.withChatSessionDomain(
    chatState: ChatCharacterState = this.chatState,
    chatContext: ChatContextState = this.chatContext,
    replySuggestions: List<ChatReplySuggestion> = this.replySuggestions,
    chatBranches: LocalChatBranchState = this.chatBranches,
    groupChat: LocalGroupChatState = this.groupChat,
): LocalHarnessSession = copy(
    chatStatePayload = encodeLocalSessionDomain(chatState).jsonObject,
    chatContextPayload = encodeLocalSessionDomain(chatContext).jsonObject,
    replySuggestionsPayload = encodeLocalSessionDomain(replySuggestions).jsonArray,
    chatBranchesPayload = encodeLocalSessionDomain(chatBranches).jsonObject,
    groupChatPayload = encodeLocalSessionDomain(groupChat).jsonObject,
)

internal fun LocalHarnessSession.withChatSessionDomain(chat: LocalChatState): LocalHarnessSession =
    copy(
        personaId = chat.personaId,
        galleryId = chat.galleryId,
        galleryStoryId = chat.galleryStoryId,
        gallerySaveSuppressedThrough = chat.gallerySaveSuppressedThrough,
    ).withChatSessionDomain(
        chatState = chat.chatState,
        chatContext = chat.chatContext,
        replySuggestions = chat.replySuggestions,
        chatBranches = chat.chatBranches,
        groupChat = chat.groupChat,
    )

/** ChatFeature interpretation/migration for Chat fields stored in the durable Session envelope. */
internal object LocalChatSessionDomainCodec : LocalSessionDomainCodec {
    override val id: String = "chat"

    override fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession {
        val decoded = session.decodeChatSessionDomain()
        val canonicalState = decoded.chatState
            .canonicalizeLegacyCharacterState()
            .withoutLegacyConversationContext()
        return session.withChatSessionDomain(
            chatState = canonicalState,
            chatContext = decoded.chatContext.withLegacyFallback(decoded.chatState),
            replySuggestions = decoded.replySuggestions,
            chatBranches = decoded.chatBranches.canonicalizeLegacyChatBranchState(),
            groupChat = decoded.groupChat.migrateLegacyConversationContext(),
        )
    }

    override fun projectSummary(
        session: LocalHarnessSession,
        summary: LocalSessionSummary,
    ): LocalSessionSummary {
        val group = session.groupChat
        return summary.copy(
            chatMode = group.mode.name,
            groupMemberCount = group.members.size,
            personaId = session.personaId,
            galleryId = session.galleryId,
        )
    }
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

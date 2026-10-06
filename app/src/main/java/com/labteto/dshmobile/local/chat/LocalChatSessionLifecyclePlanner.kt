package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.harness.session.HandoffMessage
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionDomainCreateSpec
import com.labteto.dshmobile.local.session.LocalSessionDomainModeCommand
import com.labteto.dshmobile.local.session.LocalSessionSummary
import java.io.File

internal fun shouldContinueSingleChatBinding(
    mode: LocalConversationMode,
    targetUsageMode: LocalUsageMode,
    sourceUsageMode: LocalUsageMode,
    sourceGroupEnabled: Boolean,
): Boolean =
    mode == LocalConversationMode.CONTINUATION &&
        targetUsageMode == LocalUsageMode.CHAT &&
        sourceUsageMode == LocalUsageMode.CHAT &&
        !sourceGroupEnabled

internal data class LocalChatSessionHandoffOverride(
    val value: String?,
)

internal data class LocalChatSessionCreatePlan(
    val chat: LocalChatState,
    val handoffOverride: LocalChatSessionHandoffOverride? = null,
)

internal sealed interface LocalChatSessionModeRoute {
    object Noop : LocalChatSessionModeRoute

    data class Switch(
        val sessionId: String,
    ) : LocalChatSessionModeRoute

    data class Create(
        val spec: LocalChatSessionCreateSpec,
    ) : LocalChatSessionModeRoute
}

/**
 * ChatFeature owner of Chat-specific Session creation and mode-routing policy.
 *
 * The shared Session lifecycle keeps transaction, ownership and persistence ordering. Character,
 * gallery, branch and group-chat interpretation stays here so Session orchestration does not need
 * to understand ChatFeature internals.
 */
internal class LocalChatSessionLifecyclePlanner(
    private val personaStore: ChatPersonaStore,
) {
    internal fun acceptsCreateSpec(
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec?,
    ): Boolean {
        if (domainSpec == null) return true
        val spec = domainSpec as? LocalChatSessionCreateSpec ?: return false
        if (usageMode != LocalUsageMode.CHAT) return false
        if (
            spec.chatMode == LocalChatMode.GROUP &&
            spec.groupEntries.distinctBy(PersonaGalleryEntry::id).size !in
                MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS
        ) return false
        return true
    }

    internal fun prepareCreate(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec?,
        sourceSessionId: String,
        nextSessionId: String,
        sourceUsageMode: LocalUsageMode,
        sourceChat: LocalChatState,
        sessionsRoot: File,
    ): LocalChatSessionCreatePlan {
        val spec = domainSpec as? LocalChatSessionCreateSpec
        val galleryEntry = spec?.galleryEntry
        val galleryStoryId = spec?.galleryStoryId
        val freshGalleryStory = spec?.freshGalleryStory == true
        val requestedChatMode = spec?.chatMode
        val groupEntries = spec?.groupEntries.orEmpty()
        val continueSingleChatBinding = shouldContinueSingleChatBinding(
            mode = mode,
            targetUsageMode = usageMode,
            sourceUsageMode = sourceUsageMode,
            sourceGroupEnabled = sourceChat.groupChat.enabled,
        )
        val resolvedChatMode = when {
            usageMode != LocalUsageMode.CHAT -> LocalChatMode.SINGLE
            galleryEntry != null -> LocalChatMode.SINGLE
            requestedChatMode != null -> requestedChatMode
            sourceUsageMode == LocalUsageMode.CHAT -> sourceChat.groupChat.mode
            else -> LocalChatMode.SINGLE
        }
        val selectedGalleryStory = galleryEntry?.story(galleryStoryId)
        val continuedPersona = if (
            continueSingleChatBinding &&
            sourceChat.personaId != PersonaProfile.DEFAULT_PERSONA_ID
        ) {
            personaStore.get(sourceChat.personaId)
                .takeUnless(PersonaProfile::isUnboundChatPersona)
        } else {
            null
        }
        val personaId = if (resolvedChatMode == LocalChatMode.GROUP) {
            PersonaProfile.DEFAULT_PERSONA_ID
        } else if (galleryEntry != null) {
            personaStore.upsert(
                galleryEntry.persona.copy(id = "persona-${java.util.UUID.randomUUID()}"),
            ).id
        } else {
            continuedPersona?.id ?: PersonaProfile.DEFAULT_PERSONA_ID
        }
        val chatPersona = when {
            resolvedChatMode == LocalChatMode.GROUP -> PersonaProfile()
            galleryEntry != null -> personaStore.get(personaId)
            else -> continuedPersona ?: PersonaProfile()
        }
        val chatState = if (resolvedChatMode == LocalChatMode.GROUP) {
            ChatCharacterState()
        } else if (
            galleryEntry != null &&
            usageMode == LocalUsageMode.CHAT &&
            !freshGalleryStory
        ) {
            selectedGalleryStory?.chatState?.let { storyState ->
                storyState.canonicalizeLegacyCharacterState().copy(
                    behaviorTuning = resolveCharacterBehaviorTuning(
                        storyState.behaviorTuning,
                        chatPersona.behaviorTuning,
                    ),
                )
            } ?: ChatCharacterState(behaviorTuning = chatPersona.behaviorTuning)
        } else if (continueSingleChatBinding) {
            sourceChat.chatState
        } else {
            ChatCharacterState(behaviorTuning = chatPersona.behaviorTuning)
        }

        val initialGroupMembers = if (
            resolvedChatMode == LocalChatMode.GROUP &&
            groupEntries.isNotEmpty()
        ) {
            resolveLocalGroupChatMembers(
                entries = groupEntries.take(MAX_GROUP_CHAT_MEMBERS),
                previousMembers = emptyList(),
                chatPersonaStore = personaStore,
            )
        } else {
            emptyList()
        }

        val activeBranchMessageIds = if (hasChatBranchAlternatives(sourceChat.chatBranches)) {
            activeChatBranchMessages(sourceChat.chatBranches).mapTo(hashSetOf()) { it.id }
        } else {
            null
        }
        val chatContext = when {
            resolvedChatMode == LocalChatMode.GROUP -> ChatContextState()
            galleryEntry != null && usageMode == LocalUsageMode.CHAT && !freshGalleryStory ->
                selectedGalleryStory?.chatContext?.withLegacyFallback(chatState)
                    ?: ChatContextState().withLegacyFallback(chatState)
            continueSingleChatBinding -> sourceChat.chatContext.continuePendingInSession(
                sessionsRoot,
                sourceSessionId,
                nextSessionId,
                "direct",
                activeBranchMessageIds,
            )
            else -> ChatContextState()
        }
        val continuedGroup = if (
            resolvedChatMode == LocalChatMode.GROUP &&
            mode == LocalConversationMode.CONTINUATION &&
            sourceUsageMode == LocalUsageMode.CHAT &&
            sourceChat.groupChat.enabled
        ) {
            sourceChat.groupChat.copy(
                context = sourceChat.groupChat.context.continuePendingInSession(
                    sessionsRoot,
                    sourceSessionId,
                    nextSessionId,
                    "group",
                    activeBranchMessageIds,
                ),
            )
        } else {
            null
        }

        val handoffOverride = when {
            galleryEntry != null && !freshGalleryStory ->
                LocalChatSessionHandoffOverride(
                    selectedGalleryStory?.context(galleryEntry.persona.name).orEmpty(),
                )
            mode == LocalConversationMode.CONTINUATION &&
                usageMode == LocalUsageMode.CHAT &&
                sourceUsageMode == LocalUsageMode.CHAT ->
                LocalChatSessionHandoffOverride(null)
            else -> null
        }

        return LocalChatSessionCreatePlan(
            chat = LocalChatState(
                personaId = personaId,
                galleryId = if (resolvedChatMode == LocalChatMode.GROUP) {
                    null
                } else {
                    galleryEntry?.id ?: sourceChat.galleryId.takeIf { continueSingleChatBinding }
                },
                galleryStoryId = when {
                    resolvedChatMode == LocalChatMode.GROUP -> null
                    galleryEntry != null && !freshGalleryStory -> selectedGalleryStory?.id
                    galleryEntry != null -> null
                    continueSingleChatBinding -> sourceChat.galleryStoryId
                    else -> null
                },
                gallerySaveSuppressedThrough = 0L,
                chatPersona = chatPersona,
                chatState = chatState.withoutLegacyConversationContext(),
                chatContext = chatContext,
                replySuggestions = emptyList(),
                chatBranches = LocalChatBranchState(),
                groupChat = if (resolvedChatMode == LocalChatMode.GROUP) {
                    continuedGroup ?: LocalGroupChatState(
                        mode = LocalChatMode.GROUP,
                        members = initialGroupMembers,
                    )
                } else {
                    LocalGroupChatState()
                },
                groupActiveSpeakerName = null,
                personaCorrectionNotice = null,
            ),
            handoffOverride = handoffOverride,
        )
    }

    internal fun resolveModeCommand(
        command: LocalSessionDomainModeCommand,
        usageMode: LocalUsageMode,
        chat: LocalChatState,
        sessions: List<LocalSessionSummary>,
    ): LocalChatSessionModeRoute? {
        val chatCommand = command as? LocalChatSessionModeCommand ?: return null
        return resolveMode(
            mode = chatCommand.mode,
            usageMode = usageMode,
            chat = chat,
            sessions = sessions,
        )
    }

    internal fun resolveSingleMode(
        usageMode: LocalUsageMode,
        chat: LocalChatState,
        sessions: List<LocalSessionSummary>,
    ): LocalChatSessionModeRoute =
        resolveMode(LocalChatMode.SINGLE, usageMode, chat, sessions)

    internal fun preferredSingleSession(
        sessions: List<LocalSessionSummary>,
    ): LocalSessionSummary? =
        sessions.firstOrNull {
            it.usageMode == LocalUsageMode.CHAT &&
                it.chatMode == LocalChatMode.SINGLE.name &&
                !it.blank
        } ?: sessions.firstOrNull {
            it.usageMode == LocalUsageMode.CHAT &&
                it.chatMode == LocalChatMode.SINGLE.name
        }

    internal fun singleCreateSpec(): LocalSessionDomainCreateSpec =
        LocalChatSessionCreateSpec(chatMode = LocalChatMode.SINGLE)

    internal fun handoffMessages(
        chat: LocalChatState,
        messages: List<LocalHarnessMessage>,
    ): List<HandoffMessage> =
        messages.map { message ->
            HandoffMessage(
                message.role,
                if (chat.groupChat.enabled && message.role == "assistant") {
                    groupTranscriptLine(message)
                } else {
                    message.content
                },
            )
        }

    private fun resolveMode(
        mode: LocalChatMode,
        usageMode: LocalUsageMode,
        chat: LocalChatState,
        sessions: List<LocalSessionSummary>,
    ): LocalChatSessionModeRoute {
        if (
            usageMode == LocalUsageMode.CHAT &&
            chat.groupChat.mode == mode
        ) return LocalChatSessionModeRoute.Noop

        val eligible = if (mode == LocalChatMode.GROUP) {
            listOfNotNull(findEstablishedGroupChatSession(sessions))
        } else {
            sessions.filter {
                it.usageMode == LocalUsageMode.CHAT && it.chatMode == mode.name
            }
        }
        val target = eligible.firstOrNull { !it.blank } ?: eligible.firstOrNull()
        return when {
            target != null -> LocalChatSessionModeRoute.Switch(target.id)
            mode != LocalChatMode.GROUP ->
                LocalChatSessionModeRoute.Create(LocalChatSessionCreateSpec(chatMode = mode))
            else -> LocalChatSessionModeRoute.Noop
        }
    }
}

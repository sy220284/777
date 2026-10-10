package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.Serializable

@Serializable
enum class LocalChatMode {
    SINGLE,
    GROUP,
}

@Serializable
data class LocalGroupChatMember(
    val galleryId: String,
    val personaId: String,
    val displayName: String,
    val portraitPath: String = "",
    val chatState: ChatCharacterState = ChatCharacterState(),
)

@Serializable
data class LocalGroupChatState(
    val mode: LocalChatMode = LocalChatMode.SINGLE,
    val members: List<LocalGroupChatMember> = emptyList(),
    val turnCursor: Int = 0,
    /** Shared physical scene/continuity for the whole group conversation. */
    val context: ChatContextState = ChatContextState(),
    /** Public scene premise shared with every group member in this conversation. */
    val announcement: String = "",
    /** Durable partial delivery notice; cleared only for members whose next response succeeds. */
    val failedReplyMemberIds: List<String> = emptyList(),
) {
    val enabled: Boolean get() = mode == LocalChatMode.GROUP
}

internal fun LocalGroupChatState.migrateLegacyConversationContext(): LocalGroupChatState {
    val legacyState = members.asSequence()
        .map(LocalGroupChatMember::chatState)
        .firstOrNull { state ->
            ChatContextState().withLegacyFallback(state).hasUsefulFacts()
        }
    val migratedContext = legacyState?.let(context::withLegacyFallback) ?: context
    return copy(
        context = migratedContext,
        members = members.map { member ->
            member.copy(
                chatState = member.chatState
                    .canonicalizeLegacyCharacterState()
                    .withoutLegacyConversationContext(),
            )
        },
    )
}

internal const val MAX_GROUP_CHAT_MEMBERS = 6
internal const val MIN_GROUP_CHAT_MEMBERS = 2
internal const val GROUP_CHAT_SILENT_TOKEN = "__GROUP_CHAT_SILENT__"
internal const val MAX_GROUP_CHAT_RESPONDERS_PER_TURN = 2
internal const val MAX_GROUP_CHAT_EXPLICIT_RESPONDERS_PER_TURN = MAX_GROUP_CHAT_MEMBERS

/** Only an explicit request to hear everybody expands past the normal reply budget. */
private val GROUP_CHAT_EVERYONE_CUES = listOf(
    "每个人都说", "每个人都回答", "所有人都说", "所有人都回答",
    "全部人都说", "全部人都回答", "大家依次回答", "全员依次回答", "所有角色回答",
)

private val GROUP_CHAT_COLLECTIVE_CUES = listOf(
    "你们", "大家", "各位", "所有人", "每个人", "都说", "都聊", "一起说", "一起聊",
    "分别说", "分别聊", "一个个", "轮流", "你俩", "你们俩", "两位", "三位",
)

internal fun groupChatWantsMultipleReplies(input: String): Boolean {
    val text = input.trim()
    return text.isNotBlank() && GROUP_CHAT_COLLECTIVE_CUES.any(text::contains)
}

internal fun groupChatMentionedMembers(
    input: String,
    members: List<LocalGroupChatMember>,
): List<LocalGroupChatMember> {
    val text = input.trim()
    if (text.isBlank() || members.isEmpty()) return emptyList()

    data class AddressMatch(
        val strength: Int,
        val index: Int,
        val member: LocalGroupChatMember,
    )

    fun addressMatch(member: LocalGroupChatMember): AddressMatch? {
        val name = member.displayName.trim()
        if (name.isBlank()) return null
        if (text == name) return AddressMatch(strength = 3, index = 0, member = member)

        val escaped = Regex.escape(name)
        val strongPatterns = listOf(
            Regex("""[@＠]$escaped(?=$|[\s，,。！？!?；;：:、])"""),
            Regex("""(?:^|[\s，,。！？!?；;：:、])$escaped(?=你|在吗|呢|来|帮|看|觉得|怎么|能|可以|要|想|陪|给|告诉|回答|说说)"""),
            Regex("""(?:找|问|叫|让|请|喊)$escaped(?=$|[\s，,。！？!?；;：:、]|来|帮|看|聊|说|回答|告诉)"""),
            Regex("""(?:^|[\s，,。！？!?；;：:、])$escaped\s+(?=你|在吗|呢|来|帮|看|觉得|怎么|能|可以|要|想|陪|给|告诉|回答|说说)"""),
        )
        strongPatterns.asSequence()
            .mapNotNull { regex -> regex.find(text)?.range?.first }
            .minOrNull()
            ?.let { return AddressMatch(strength = 3, index = it, member = member) }

        val punctuationIndex = Regex("""(?:^|[\s，,。！？!?；;：:、])$escaped(?=$|[，,。！？!?；;：:、])""")
            .find(text)
            ?.range
            ?.first
            ?: return null
        return AddressMatch(strength = 2, index = punctuationIndex, member = member)
    }

    val matches = members.mapNotNull(::addressMatch)
    val strongest = matches.maxOfOrNull(AddressMatch::strength) ?: return emptyList()
    return matches.asSequence()
        .filter { it.strength == strongest }
        .sortedBy(AddressMatch::index)
        .map(AddressMatch::member)
        .distinctBy(LocalGroupChatMember::galleryId)
        .take(MAX_GROUP_CHAT_EXPLICIT_RESPONDERS_PER_TURN)
        .toList()
}

/** Secondary speakers may listen naturally; direct or explicitly all-member requests still expect replies. */
internal fun groupMemberMayStaySilent(input: String, member: LocalGroupChatMember, index: Int): Boolean =
    index > 0 &&
        GROUP_CHAT_EVERYONE_CUES.none(input::contains) &&
        (member.displayName.isBlank() || !input.contains(member.displayName))

internal fun groupChatResponders(
    input: String,
    members: List<LocalGroupChatMember>,
): List<LocalGroupChatMember> {
    if (members.isEmpty()) return emptyList()
    val distinct = members.distinctBy(LocalGroupChatMember::galleryId)
    // When the user clearly requests each participant, honor the actual 2–6 person
    // roster; the normal one/two speaker budget still applies to casual group chat.
    if (GROUP_CHAT_EVERYONE_CUES.any(input::contains)) {
        return distinct.take(MAX_GROUP_CHAT_MEMBERS)
    }
    val explicitlyMentioned = groupChatMentionedMembers(input, distinct)
    if (explicitlyMentioned.isNotEmpty()) return explicitlyMentioned

    return if (groupChatWantsMultipleReplies(input)) {
        distinct.take(MAX_GROUP_CHAT_RESPONDERS_PER_TURN)
    } else {
        distinct.take(1)
    }
}

internal fun stripGroupSpeakerPrefix(
    content: String,
    vararg speakerNames: String?,
): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return trimmed

    val names = speakerNames.asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .sortedByDescending { it.length }

    names.forEach { name ->
        val prefixes = sequenceOf(
            "**$name：**", "**$name:**", "__${name}：__", "__${name}:__",
            "【$name】：", "【$name】:", "[$name]：", "[$name]:",
            "@$name：", "@$name:", "＠$name：", "＠$name:",
            "$name：", "$name:",
            "**$name**\r\n", "**$name**\n", "__${name}__\r\n", "__${name}__\n",
            "【$name】\r\n", "【$name】\n", "[$name]\r\n", "[$name]\n",
            "$name\r\n", "$name\n",
            "$name - ", "$name — ", "$name— ",
        )
        prefixes.firstOrNull(trimmed::startsWith)?.let { prefix ->
            return trimmed.removePrefix(prefix).trimStart()
        }
    }
    return trimmed
}

internal fun groupMessageVisibleContent(message: LocalHarnessMessage): String =
    if (message.role == "assistant") {
        stripGroupSpeakerPrefix(message.content, message.speakerName)
    } else {
        message.content
    }

internal fun groupTranscriptLine(message: LocalHarnessMessage): String = when (message.role) {
    "user" -> "用户：${message.content}"
    "assistant" -> {
        val speaker = message.speakerName?.takeIf(String::isNotBlank) ?: "角色"
        "$speaker：${groupMessageVisibleContent(message)}"
    }
    else -> message.content
}

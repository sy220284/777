package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaProfile
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
    val persona: PersonaProfile = PersonaProfile(),
    val chatState: ChatCharacterState = ChatCharacterState(),
)

@Serializable
data class LocalGroupChatState(
    val mode: LocalChatMode = LocalChatMode.SINGLE,
    val members: List<LocalGroupChatMember> = emptyList(),
    val turnCursor: Int = 0,
    /** Public scene premise shared with every group member in this conversation. */
    val announcement: String = "",
) {
    val enabled: Boolean get() = mode == LocalChatMode.GROUP
}

internal const val MAX_GROUP_CHAT_MEMBERS = 6
internal const val MIN_GROUP_CHAT_MEMBERS = 2
internal const val GROUP_CHAT_SILENT_TOKEN = "__GROUP_CHAT_SILENT__"
internal const val MAX_GROUP_CHAT_RESPONDERS_PER_TURN = 2
internal const val MAX_GROUP_CHAT_EXPLICIT_RESPONDERS_PER_TURN = 3

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

internal fun groupChatResponders(
    input: String,
    members: List<LocalGroupChatMember>,
): List<LocalGroupChatMember> {
    if (members.isEmpty()) return emptyList()
    val explicitlyMentioned = groupChatMentionedMembers(input, members)
    if (explicitlyMentioned.isNotEmpty()) return explicitlyMentioned

    val distinct = members.distinctBy(LocalGroupChatMember::galleryId)
    return if (groupChatWantsMultipleReplies(input)) {
        distinct.take(MAX_GROUP_CHAT_RESPONDERS_PER_TURN)
    } else {
        distinct.take(1)
    }
}

internal fun groupTranscriptLine(message: LocalHarnessMessage): String = when (message.role) {
    "user" -> "用户：${message.content}"
    "assistant" -> {
        val speaker = message.speakerName?.takeIf(String::isNotBlank) ?: "角色"
        "$speaker：${message.content}"
    }
    else -> message.content
}

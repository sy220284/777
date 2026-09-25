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
    val persona: PersonaProfile = PersonaProfile(),
    val chatState: ChatCharacterState = ChatCharacterState(),
)

@Serializable
data class LocalGroupChatState(
    val mode: LocalChatMode = LocalChatMode.SINGLE,
    val members: List<LocalGroupChatMember> = emptyList(),
    val turnCursor: Int = 0,
) {
    val enabled: Boolean get() = mode == LocalChatMode.GROUP
}

internal const val MAX_GROUP_CHAT_MEMBERS = 6
internal const val MIN_GROUP_CHAT_MEMBERS = 2
internal const val GROUP_CHAT_SILENT_TOKEN = "__GROUP_CHAT_SILENT__"
internal const val MAX_GROUP_CHAT_RESPONDERS_PER_TURN = 3

internal fun groupChatMentionedMembers(
    input: String,
    members: List<LocalGroupChatMember>,
): List<LocalGroupChatMember> {
    val text = input.trim()
    if (text.isBlank() || members.isEmpty()) return emptyList()

    fun positionOfAny(name: String, patterns: List<String>): Int =
        patterns.asSequence()
            .map { pattern -> text.indexOf(pattern) }
            .filter { it >= 0 }
            .minOrNull()
            ?: -1

    val direct = members.mapNotNull { member ->
        val name = member.displayName.trim()
        if (name.isBlank()) return@mapNotNull null
        val index = positionOfAny(
            name,
            listOf(
                "@$name", "＠$name",
                "$name你", "$name，", "$name,", "$name：", "$name:", "$name、",
            ),
        )
        index.takeIf { it >= 0 }?.let { it to member }
    }.sortedBy { it.first }

    val selected = if (direct.isNotEmpty()) {
        direct
    } else {
        members.mapNotNull { member ->
            val name = member.displayName.trim()
            val index = name.takeIf(String::isNotBlank)?.let(text::indexOf) ?: -1
            index.takeIf { it >= 0 }?.let { it to member }
        }.sortedBy { it.first }
    }

    return selected.map { it.second }
        .distinctBy(LocalGroupChatMember::galleryId)
        .take(MAX_GROUP_CHAT_MEMBERS)
}

internal fun groupChatResponders(
    input: String,
    members: List<LocalGroupChatMember>,
): List<LocalGroupChatMember> {
    if (members.isEmpty()) return emptyList()
    val explicitlyMentioned = groupChatMentionedMembers(input, members)
    return if (explicitlyMentioned.isNotEmpty()) {
        explicitlyMentioned
    } else {
        members
            .distinctBy(LocalGroupChatMember::galleryId)
            .take(MAX_GROUP_CHAT_RESPONDERS_PER_TURN)
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

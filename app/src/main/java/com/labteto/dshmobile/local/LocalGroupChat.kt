package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
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
    val chatState: ChatCharacterState = ChatCharacterState(),
)

@Serializable
data class LocalGroupChatState(
    val mode: LocalChatMode = LocalChatMode.SINGLE,
    val members: List<LocalGroupChatMember> = emptyList(),
) {
    val enabled: Boolean get() = mode == LocalChatMode.GROUP
}

internal const val MAX_GROUP_CHAT_MEMBERS = 6
internal const val MIN_GROUP_CHAT_MEMBERS = 2
internal const val GROUP_CHAT_SILENT_TOKEN = "__GROUP_CHAT_SILENT__"

internal fun groupChatResponders(
    input: String,
    members: List<LocalGroupChatMember>,
): List<LocalGroupChatMember> {
    if (members.isEmpty()) return emptyList()
    val normalizedInput = input.trim()
    val explicitlyMentioned = members.filter { member ->
        val name = member.displayName.trim()
        name.isNotBlank() && (
            "@$name" in normalizedInput ||
                "＠$name" in normalizedInput ||
                normalizedInput.startsWith("$name，") ||
                normalizedInput.startsWith("$name,") ||
                normalizedInput.startsWith("$name：") ||
                normalizedInput.startsWith("$name:")
            )
    }
    return (if (explicitlyMentioned.isNotEmpty()) explicitlyMentioned else members)
        .distinctBy(LocalGroupChatMember::galleryId)
        .take(MAX_GROUP_CHAT_MEMBERS)
}

internal fun groupTranscriptLine(message: LocalHarnessMessage): String = when (message.role) {
    "user" -> "用户：${message.content}"
    "assistant" -> {
        val speaker = message.speakerName?.takeIf(String::isNotBlank) ?: "角色"
        "$speaker：${message.content}"
    }
    else -> message.content
}

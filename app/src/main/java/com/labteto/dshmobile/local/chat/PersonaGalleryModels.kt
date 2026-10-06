package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.Serializable

@Serializable
data class PersonaGalleryStory(
    val id: String,
    val title: String = "",
    val notes: String = "",
    /** Recent hot window only. Complete dialogue is stored in the per-story cold archive. */
    val history: List<LocalHarnessMessage> = emptyList(),
    val historyTotalCount: Int = history.size,
    val historyArchived: Boolean = false,
    val chatState: ChatCharacterState = ChatCharacterState(),
    val chatContext: ChatContextState = ChatContextState(),
    val sourceSessionIds: List<String> = emptyList(),
    val excludedMessageKeys: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    fun context(personaName: String): String = buildString {
        if (notes.isNotBlank()) appendLine("剧情提要：${notes.trim().take(2_500)}")
        if (chatState.updatedAt > 0L) {
            appendLine("保存时的关系：${chatState.relationshipState.take(80)}")
            val shared = chatContext.withLegacyFallback(chatState)
            val scene = shared.scene
            if (scene.sceneTime.isNotBlank() || scene.location.isNotBlank()) {
                appendLine(
                    "当前硬场景：时间=${scene.sceneTime.ifBlank { "未知" }}｜地点=${scene.location.ifBlank { "未知" }}",
                )
                appendLine("人物位置、进行中动作和物件不从旧场景快照继承，以新会话最近原始对话为准。")
            }
            shared.continuity.recentEvents.takeLast(5).takeIf { it.isNotEmpty() }?.let {
                appendLine("近期关键事件：${it.joinToString("；").take(900)}")
            }
            shared.continuity.decisions.takeLast(4).takeIf { it.isNotEmpty() }?.let {
                appendLine("当前有效决定：${it.joinToString("；").take(720)}")
            }
            shared.continuity.unfinished.takeLast(4).takeIf { it.isNotEmpty() }?.let {
                appendLine("待续事项：${it.joinToString("；").take(720)}")
            }
            chatState.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let {
                appendLine("已发生的共同经历：${it.joinToString("；").take(800)}")
            }
            chatState.dynamics.sharedObjects.takeLast(6).takeIf { it.isNotEmpty() }?.let {
                appendLine("仍有生命的小物件、共同梗和约定：${it.joinToString("；").take(700)}")
            }
            chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let {
                appendLine("仍待推进的线索：${it.joinToString("；").take(400)}")
            }
        }
        val userExcerpt = history.asReversed().asSequence()
            .filter { it.role == "user" }
            .map { "用户：${it.content.trim().take(500)}" }
            .filter { it.length > 3 }
            .take(6)
            .toList()
            .asReversed()
            .joinToString("\n")
            .takeLast(2_000)
        if (userExcerpt.isNotBlank()) {
            appendLine("已保存故事的近期用户表达与事件：\n$userExcerpt")
        }
        if (history.any { it.role == "assistant" }) {
            appendLine("角色旧回复已归档，不作为新会话台词重复注入。")
        }
    }.trim()
}

/** One durable character master profile. Story timelines are isolated under [stories]. */
@Serializable
data class PersonaGalleryEntry(
    val id: String,
    val persona: PersonaProfile,
    /** App-private character artwork used by the gallery's spatial standee presentation. */
    val portraitPath: String = "",
    val groupChatState: ChatCharacterState = ChatCharacterState(),
    val stories: List<PersonaGalleryStory> = emptyList(),
    val updatedAt: Long = 0L,
) {
    fun story(storyId: String?): PersonaGalleryStory? =
        storyId?.let { id -> stories.firstOrNull { it.id == id } }
            ?: stories.maxByOrNull(PersonaGalleryStory::updatedAt)

    fun storyContext(storyId: String? = null): String =
        story(storyId)?.context(persona.name).orEmpty()

    fun totalDialogueCount(): Int = stories.sumOf { story ->
        maxOf(
            story.historyTotalCount,
            story.history.count { it.role == "user" || it.role == "assistant" },
        )
    }
}

data class PersonaGallerySaveOutcome(
    val entry: PersonaGalleryEntry,
    val storyId: String?,
)

@Serializable
internal data class PersonaShareEnvelope(
    val schema: Int = 2,
    val source: String = "神言神语",
    val persona: PersonaProfile,
)

@Serializable
internal data class GalleryDocument(
    val version: Int = 5,
    val entries: List<PersonaGalleryEntry> = emptyList(),
)


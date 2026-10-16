package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.LocalMediaKind

/** A user-editable draft, not a second model request or automatically paid task. */
internal fun mediaCreativeDraft(
    kind: LocalMediaKind,
    reply: String,
    personaName: String,
    personaIdentity: String,
): String {
    val message = reply.trim().take(1800)
    // Visual generation consumes visible stage directions, not the actor's spoken dialogue.
    val stageDirections = Regex("（([^）]+)）|\\(([^)]+)\\)")
        .findAll(message).map { it.value.drop(1).dropLast(1).trim() }
        .filter(String::isNotBlank).take(8).joinToString("；")
    val character = personaName.trim().take(60).takeUnless { it.isBlank() || it == "默认角色" }
    val identity = personaIdentity.trim().take(500)
    return when (kind) {
        LocalMediaKind.IMAGE, LocalMediaKind.VIDEO -> buildString {
            append(if (kind == LocalMediaKind.IMAGE)
                "生成一幅无文字、字幕、对话框、水印的单幅插画。"
                else "制作一段无字幕、对话框、水印的短视频。")
            if (character != null) append("\n画面人物：${character}。")
            if (identity.isNotBlank()) append("\n人物身份和已知形象设定：${identity}。不要猜测未提供的外貌细节。")
            append("\n仅表现以下场景的可见动作、环境和情绪，不要把对白绘制成字：\n")
            append(stageDirections.ifBlank { message.take(200) })
        }
        LocalMediaKind.SPEECH -> spokenDialogueOnly(reply)
        LocalMediaKind.TRANSCRIBE -> ""
    }
}

/** A media type must never inherit a different type's creation instructions. */
internal fun initialMediaTextForKind(
    kind: LocalMediaKind,
    initialKind: LocalMediaKind,
    initialPrompt: String,
    sourceReply: String?,
    personaName: String,
    personaIdentity: String,
): String = when {
    kind == initialKind -> initialPrompt
    sourceReply != null -> mediaCreativeDraft(kind, sourceReply, personaName, personaIdentity)
    else -> ""
}

private val spokenContentHeader = Regex(
    """(?m)^[ \t]*(?:朗读内容|配音内容|配音文本|配音文案|朗读文本|需要朗读的文字|台词|正文)[ \t]*[：:][ \t]*"""
)
private val nonSpokenDirective = Regex(
    """^(?:提示词|语音提示词|配音提示词|朗读提示|朗读要求|配音要求|音色|语气|情绪|风格)[ \t]*[：:].*"""
)

/**
 * Keep manually entered dialogue literal unless it contains explicit labeled
 * creation instructions. In that case, send only the labeled spoken section.
 * Never silently truncate billable manual TTS: provider limits are checked first.
 */
internal fun speechPayloadText(input: String): String {
    val text = input.trim()
    val hasCreationInstructions = spokenContentHeader.containsMatchIn(text) ||
        text.lineSequence().any { nonSpokenDirective.matches(it.trim()) }
    return if (hasCreationInstructions) spokenDialogueOnly(text, Int.MAX_VALUE) else text
}

/** Only audible text goes into TTS; explicit creative directions are not dialogue. */
internal fun spokenDialogueOnly(reply: String, limit: Int = 1800): String {
    val content = spokenContentHeader.find(reply)?.let { reply.substring(it.range.last + 1) } ?: reply
    return content.replace(Regex("（[^）]*）|\\([^)]*\\)|^[ \\t]*\\*[^*\\n]+\\*", RegexOption.MULTILINE), "")
        .replace(Regex("\\*([^*\\n]+)\\*"), "$1")
        .lines().map(String::trim).filter(String::isNotBlank)
        .filterNot(nonSpokenDirective::matches)
        .joinToString("\n").trim().take(limit)
}

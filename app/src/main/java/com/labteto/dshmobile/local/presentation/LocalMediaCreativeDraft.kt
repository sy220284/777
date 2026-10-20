package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.LocalMediaKind

/**
 * A reply can contain spoken dialogue, explanations or genuine visual description.
 * Only visual material is eligible for the prefilled image/video prompt. Sending
 * non-visual prose to a generation model makes it draw paragraphs as lettering.
 */
private val visualSceneCue = Regex(
    """站在|坐在|躺在|卧在|走进|走出|走向|走过|行走|漫步|奔跑|跑向|转身|回头|抬头|低头|俯身|伸手|挥手|举起|握着|手持|抱着|捧着|端起|穿着|身穿|披着|望向|看向|注视|微笑|大笑|哭泣|流泪|皱眉|蹙眉|点头|摇头|眼神|表情|灯光|光线|阳光|月光|夕阳|日落|黄昏|黎明|夜色|雨幕|风雪|竹林|街道|房间|庭院|海边|山顶|画面|镜头|场景|景色"""
)

private fun visualSceneFromReply(message: String): String {
    // Parenthesized stage directions can be genuine actions or just notes
    // about software. Keep only drawable actions, without enclosing dialogue.
    val parentheses = Regex("（([^）]+)）|\\(([^)]+)\\)")
    val stage = parentheses.findAll(message)
        .map { it.value.drop(1).dropLast(1).trim() }
        .filter { visualSceneCue.containsMatchIn(it) }
        .take(8).joinToString("；")

    // Extract visual narrative separately. Otherwise an inline action in
    // parentheses can pull all of the surrounding chatbot explanation back in.
    val narrative = message.replace(parentheses, "")
        .split(Regex("[。！？\\n]")).asSequence()
        .map { it.trim().trimStart('•', '-', '*').trim() }
        .filter { it.length in 4..200 && visualSceneCue.containsMatchIn(it) }
        .take(4).joinToString("；")
    return listOf(narrative, stage).filter(String::isNotBlank).joinToString("；").take(600)
}

/** A user-editable draft, not a second model request or automatically paid task. */
internal fun mediaCreativeDraft(
    kind: LocalMediaKind,
    reply: String,
    personaName: String,
    personaIdentity: String,
): String {
    val message = reply.trim().take(1800)
    val character = personaName.trim().take(60).takeUnless { it.isBlank() || it == "默认角色" }
    // A biography or capability description must not masquerade as a visible
    // character design. Keep explicitly visual appearance details only.
    val appearance = visualSceneFromReply(personaIdentity.trim().take(500)).takeIf(String::isNotBlank)
    return when (kind) {
        LocalMediaKind.IMAGE, LocalMediaKind.VIDEO -> {
            val scene = visualSceneFromReply(message)
            if (scene.isBlank()) "" else buildString {
                append(if (kind == LocalMediaKind.IMAGE)
                    "生成一幅单幅插画，描绘以下具体画面。" else "制作一段短视频，描绘以下具体画面。")
                if (character != null) append("\\n画面人物：${character}。")
                if (appearance != null) append("\\n人物外观设定：${appearance}。")
                append("\\n画面内容：${scene}")
                append("\\n只用人物、动作、环境和神态表达内容。画面内不得出现文字、字母、数字、字幕、对话框、标识或水印；不要制作文字海报或说明卡。")
            }
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

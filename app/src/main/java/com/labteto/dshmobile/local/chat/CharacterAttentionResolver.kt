package com.labteto.dshmobile.local.chat

internal data class CharacterAttentionProjection(
    val noticed: List<String> = emptyList(),
    val possibleBlindSpot: String = "",
)

internal fun resolveCharacterAttention(
    persona: PersonaProfile,
    state: ChatCharacterState,
    userInput: String,
): CharacterAttentionProjection {
    val clauses = userInput
        .split(Regex("[，。！？!?；;\\n]+"))
        .map(String::trim)
        .filter { it.length >= 2 }
        .take(10)
    if (clauses.isEmpty()) return CharacterAttentionProjection()

    val scored = clauses.mapIndexed { index, clause ->
        var score = if (index == 0) 1 else 0
        if (isHighPriorityUserClause(clause)) score += 8
        score += persona.attentionKeywords.count { attentionKeywordMatches(it, clause) } * 6
        score += persona.attentionBiases.count { attentionBiasMatches(it, clause) } * 3
        if (relatedAttentionText(state.currentFocus, clause)) score += 3
        if (relatedAttentionText(state.immediateConcern, clause)) score += 2
        if (relatedAttentionText(state.currentAgenda, clause)) score += 1
        clause to score
    }
    val noticed = scored
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenBy { clauses.indexOf(it.first) },
        )
        .map(Pair<String, Int>::first)
        .distinct()
        .take(2)

    val directBlindSpot = persona.perceptionBlindSpots.firstOrNull {
        relatedAttentionText(it, userInput)
    }

    return CharacterAttentionProjection(
        noticed = noticed,
        possibleBlindSpot = directBlindSpot.orEmpty().take(160),
    )
}

internal fun renderCharacterAttentionPrompt(projection: CharacterAttentionProjection): String {
    if (projection.noticed.isEmpty() && projection.possibleBlindSpot.isBlank()) return ""
    return buildString {
        appendLine("【本轮注意力】")
        if (projection.noticed.isNotEmpty()) {
            appendLine("第一眼更容易接住：${projection.noticed.joinToString("；")}")
        }
        projection.possibleBlindSpot.takeIf(String::isNotBlank)?.let {
            appendLine("已有认知盲点可能影响理解：$it")
        }
        append(
            "注意力只决定自然篇幅和先后，不制造误解；真正的信息型问题、拒绝、边界、承诺和重要事实必须优先准确处理。",
        )
    }.trim()
}

internal fun relatedAttentionText(left: String, right: String): Boolean {
    val a = normalizeAttentionText(left)
    val b = normalizeAttentionText(right)
    if (a.length < 2 || b.length < 2) return false
    if (a.contains(b) || b.contains(a)) return true
    val grams = if (a.length <= 3) setOf(a) else a.windowed(2).toSet()
    val hits = grams.count(b::contains)
    return hits >= 2 && hits * 2 >= grams.size.coerceAtMost(6)
}

internal fun isHighPriorityUserInput(text: String): Boolean =
    text.split(Regex("[，。！？!?；;\\n]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .any(::isHighPriorityUserClause)

private fun isHighPriorityUserClause(text: String): Boolean =
    INFORMATION_QUESTION.containsMatchIn(text) ||
        HARD_BOUNDARY_OR_COMMITMENT.containsMatchIn(text) ||
        DIRECT_BIE_BOUNDARY.containsMatchIn(text)

private fun attentionBiasMatches(bias: String, text: String): Boolean {
    if (relatedAttentionText(bias, text)) return true
    val normalizedText = normalizeAttentionText(text)
    return bias.split(Regex("(?:和|与|以及|、|的|对|会|容易|偏向|常常|通常)"))
        .asSequence()
        .map(::normalizeAttentionText)
        .filter { it.length >= 2 }
        .any(normalizedText::contains)
}

private fun attentionKeywordMatches(keyword: String, text: String): Boolean {
    val cleanKeyword = keyword.trim().lowercase()
    if (cleanKeyword.isBlank()) return false
    val cleanText = text.lowercase()
    return if (cleanKeyword.all { it.code < 128 && (it.isLetterOrDigit() || it == '_' || it == '-') }) {
        Regex("""(?<![A-Za-z0-9_-])${Regex.escape(cleanKeyword)}(?![A-Za-z0-9_-])""")
            .containsMatchIn(cleanText)
    } else {
        normalizeAttentionText(cleanText).contains(normalizeAttentionText(cleanKeyword))
    }
}

private fun normalizeAttentionText(text: String): String =
    text.lowercase().replace(Regex("[\\s，。！？；：、,.!?;:'\"“”‘’()（）\\[\\]【】_-]+"), "")

private val INFORMATION_QUESTION = Regex(
    """(?:为什么|怎么(?:办|做|处理|解决|回事|会|能|才|才能|突然)|多少|几(?:点|个|次|天|年|岁)|什么时候|哪(?:里|儿|个|种|些)|是否|能不能|可不可以|有没有|是不是|该不该|要不要|谁(?:会|能|是)?|什么(?:时候|原因|情况|意思|方法|东西))""",
)

private val HARD_BOUNDARY_OR_COMMITMENT = Regex(
    """(?:不要|不能|必须|拒绝|答应|约好|记住|不许|禁止)""",
)

private val DIRECT_BIE_BOUNDARY = Regex(
    """别(?:说|做|问|提|碰|动|发|叫|联系|告诉|再|给|把|让|替|管|来|去|开|关|拿|看|想|催|劝|靠近|离开|跟|抢)""",
)

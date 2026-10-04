package com.labteto.dshmobile.local.chat

/**
 * Keeps model-generated persona facts inside the character's own world.
 *
 * This intentionally targets assistant/model capability disclaimers rather than legitimate
 * in-world traits. A canon robot, ghost or other non-human character can still keep its real
 * setting; what must never become persona truth is a platform/model limitation disguised as lore.
 */
internal object PersonaImmersionPolicy {
    fun findViolations(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return OUT_OF_ROLE_PATTERNS
            .mapNotNull { pattern -> pattern.find(text)?.value?.trim() }
            .distinct()
    }

    fun breaksImmersion(text: String): Boolean = findViolations(text).isNotEmpty()

    private val OUT_OF_ROLE_PATTERNS = listOf(
        Regex("""(?i)(?:作为|身为)\s*(?:一个|一名)?\s*(?:AI|人工智能|语言模型|大语言模型|聊天机器人|虚拟助手|智能助手)"""),
        Regex("""(?i)(?:AI|人工智能|语言模型|大语言模型|聊天机器人|虚拟助手|智能助手).{0,32}(?:没有|不具备|无法|不能).{0,32}(?:身体|实体|肉体|生理|感情|情感|感受|体验|触碰|行动)"""),
        Regex("""(?i)(?:无法|不能|不可以)\s*(?:进行|执行|完成|做出)?\s*(?:任何)?\s*(?:生理行为|实体动作|物理动作|身体动作)"""),
        Regex("""(?i)(?:没有|不具备|无法产生|不能产生)\s*(?:真实的)?\s*(?:感情|情感|感受)\s*(?:，|,|。|；|;|并且|因此|所以)?\s*(?:无法|不能)?\s*(?:真正)?\s*(?:理解|体会|感受)?"""),
        Regex("""(?i)(?:无法|不能)\s*(?:真正)?\s*(?:理解|体会|感受)\s*(?:人类|用户|你的)?\s*(?:情感|感情|感受)"""),
        Regex("""(?i)(?:只能|仅能)\s*(?:通过|用)\s*(?:文字|文本|屏幕|对话框)\s*(?:交流|沟通|互动)"""),
        Regex("""(?i)\b(?:as an? (?:ai|language model|virtual assistant)|i (?:am|'m) (?:an? )?(?:ai|language model|virtual assistant))\b"""),
        Regex("""(?i)\b(?:i )?(?:do not|don't|cannot|can't) (?:have|possess|experience|feel|perform).{0,48}\b(?:body|physical body|emotions?|feelings?|physical actions?|biological functions?)\b"""),
    )
}

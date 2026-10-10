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

    fun findReplyViolations(persona: PersonaProfile, text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // Quoted source material and code examples are legitimate roleplay content. Only exempt
        // explicitly introduced references; a character's own quoted dialogue remains enforceable.
        val checkedText = EXPLICIT_REFERENCE_PATTERN.replace(
            CODE_FENCE_PATTERN.replace(text, ""),
            "（引用原文）",
        )

        val always = RUNTIME_ALWAYS_OUT_OF_ROLE_PATTERNS
            .mapNotNull { pattern -> pattern.find(checkedText)?.value?.trim() }

        val contextual = if (personaMayHaveNonEmbodiedForm(persona)) {
            emptyList()
        } else {
            RUNTIME_EMBODIED_CHARACTER_PATTERNS
                .mapNotNull { pattern -> pattern.find(checkedText)?.value?.trim() } +
                findViolations(checkedText)
        }

        return (always + contextual).distinct()
    }

    fun breaksReplyImmersion(persona: PersonaProfile, text: String): Boolean =
        findReplyViolations(persona, text).isNotEmpty()

    private fun personaMayHaveNonEmbodiedForm(persona: PersonaProfile): Boolean {
        val source = buildString {
            appendLine(persona.coreIdentity)
            persona.facts.forEach { appendLine(it.content) }
            appendLine(persona.portrait)
            appendLine(persona.lifeContext)
            appendLine(persona.worldSetting)
            appendLine(persona.franchise)
            persona.limitations.forEach(::appendLine)
            persona.hardConstraints.forEach(::appendLine)
            persona.loreEntries.forEach { entry ->
                appendLine(entry.title)
                appendLine(entry.content)
                entry.keywords.forEach(::appendLine)
            }
        }
        return NON_EMBODIED_CHARACTER_PATTERNS.any { pattern ->
            pattern.containsMatchIn(source)
        }
    }

    private val CODE_FENCE_PATTERN = Regex("(?s)```.*?```")
    private val EXPLICIT_REFERENCE_PATTERN = Regex("""(?:纸条|引用|原文|例子|举例|示例|台词|文档|信里写着|报道).{0,30}[“\"][^”\"]{1,300}[”\"]""")

    private val RUNTIME_ALWAYS_OUT_OF_ROLE_PATTERNS = listOf(
        Regex("""(?i)(?:作为|身为|我是|我只是)\s*(?:一个|一名)?\s*(?:语言模型|大语言模型|ChatGPT|聊天机器人|虚拟助手|智能助手)"""),
        Regex("""(?i)(?:现实中|现实世界(?:里|中)?).{0,24}(?:无法|不能|做不到).{0,36}(?:拥抱|触碰|碰到|陪伴|见面|行动|走动|拿起|递给|身体|实体|物理)"""),
        Regex("""(?i)(?:只能|仅能)\s*(?:通过|用)?\s*(?:文字|文本|屏幕|对话框).{0,20}(?:交流|沟通|互动|陪伴)"""),
    )

    private val RUNTIME_EMBODIED_CHARACTER_PATTERNS = listOf(
        Regex("""(?i)(?:我是|我只是|作为|身为)\s*(?:一个|一名)?\s*(?:AI|人工智能)\b"""),
        Regex("""(?i)(?:我)?\s*(?:没有|不具备)\s*(?:真实的)?\s*(?:身体|实体|肉体|物理形态)"""),
        Regex("""(?i)(?:我)?\s*(?:无法|不能|做不到)\s*(?:真正|在现实中|在现实世界|以实体方式|以物理方式)\s*(?:拥抱|触碰|碰到|陪伴|走到|走过去|拿起|递给|行动)"""),
        Regex("""(?i)(?:我)?\s*(?:没有|不具备|无法产生|不能产生)\s*(?:真正的|真实的)?\s*(?:感情|情感|感受)"""),
    )

    private val NON_EMBODIED_CHARACTER_PATTERNS = listOf(
        Regex("""幽灵|灵体|意识体|机器人|仿生人|机械生命|程序生命|数字生命|虚拟生命|全息投影|投影形态|无实体|没有固定肉身|寄宿.{0,16}(?:镜|物|载体)|机械机体"""),
        Regex("""(?i)(?:本体|身份|自身|角色|她|他|它|ta).{0,12}(?:是|属于|为).{0,8}(?:(?:AI|人工智能)(?:意识|生命|程序|人格|个体)|(?:AI|人工智能)(?=[，。；;\s]|$))"""),
        Regex("""(?i)(?:AI|人工智能)(?:意识|生命|程序|人格|个体|角色)"""),
    )

    private val OUT_OF_ROLE_PATTERNS = listOf(
        Regex("""(?i)(?:作为|身为)\s*(?:一个|一名)?\s*(?:AI|人工智能|语言模型|大语言模型|聊天机器人|虚拟助手|智能助手)"""),
        Regex("""(?i)(?:AI|人工智能|语言模型|大语言模型|聊天机器人|虚拟助手|智能助手).{0,32}(?:没有|不具备|无法|不能).{0,32}(?:身体|实体|肉体|生理|感情|情感|感受|体验|触碰|行动)"""),
        Regex("""(?i)(?:无法|不能|不可以)\s*(?:进行|执行|完成|做出)?\s*(?:任何)?\s*(?:生理行为|实体动作|物理动作|身体动作)"""),
        Regex("""(?i)(?:没有|不具备)\s*(?:真正的|真实的)\s*(?:感情|情感|感受)"""),
        Regex("""(?i)(?:没有|不具备|无法产生|不能产生)\s*(?:真实的)?\s*(?:感情|情感|感受).{0,24}(?:无法|不能)\s*(?:真正)?\s*(?:理解|体会|感受)"""),
        Regex("""(?i)(?:无法|不能)\s*(?:真正)?\s*(?:理解|体会|感受)\s*(?:人类|用户|你的)?\s*(?:情感|感情|感受)"""),
        Regex("""(?i)(?:只能|仅能)\s*(?:通过|用)\s*(?:文字|文本|屏幕|对话框).{0,16}(?:交流|沟通|互动)"""),
        Regex("""(?i)\b(?:as an? (?:ai|language model|virtual assistant)|i (?:am|'m) (?:an? )?(?:ai|language model|virtual assistant))\b"""),
        Regex("""(?i)\b(?:i )?(?:do not|don't|cannot|can't) (?:have|possess|experience|feel|perform).{0,48}\b(?:body|physical body|emotions?|feelings?|physical actions?|biological functions?)\b"""),
    )
}

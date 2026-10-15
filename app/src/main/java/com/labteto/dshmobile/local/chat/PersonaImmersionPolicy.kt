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

        val authoredAI = personaHasAuthoredAIIdentity(persona)
        val always = RUNTIME_ALWAYS_OUT_OF_ROLE_PATTERNS
            .mapNotNull { pattern -> pattern.find(checkedText)?.value?.trim() }
            .filterNot { claim -> authoredAI && (!claim.contains("ChatGPT", ignoreCase = true) ||
                persona.coreIdentity.contains("ChatGPT", ignoreCase = true) ||
                persona.name.equals("ChatGPT", ignoreCase = true)) }

        val contextual = if (authoredAI) {
            emptyList()
        } else {
            RUNTIME_EMBODIED_CHARACTER_PATTERNS
                .mapNotNull { pattern -> pattern.find(checkedText)?.value?.trim() }
        }

        return (always + contextual).distinct()
    }

    fun breaksReplyImmersion(persona: PersonaProfile, text: String): Boolean =
        findReplyViolations(persona, text).isNotEmpty()

    private fun personaHasAuthoredAIIdentity(persona: PersonaProfile): Boolean {
        val source = buildString {
            appendLine(persona.name)
            appendLine(persona.coreIdentity)
            appendLine(persona.trustedFactText(CharacterFactCategories.SELF_NARRATIVE))
        }
        return source.lineSequence().any { line ->
            AUTHORED_AI_IDENTITY.findAll(line).any { match ->
                val before = line.take(match.range.first).takeLast(12)
                val after = line.drop(match.range.last + 1).take(12)
                !Regex("并非|不是|并不属于|不自称|不可自称|不要自称|不得自称|禁止自称|研究|开发|维护")
                    .containsMatchIn(before) &&
                    !Regex("^\\s*(?:研究员|研究者|开发者|工程师|researcher|engineer)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(after)
            }
        }
    }

    private val CODE_FENCE_PATTERN = Regex("(?s)```.*?```")
    private val EXPLICIT_REFERENCE_PATTERN = Regex("""(?:纸条|引用|原文|例子|举例|示例|台词|文档|信里写着|报道).{0,30}[“\"][^”\"]{1,300}[”\"]""")

    // A limitation or an emotion is not evidence of leaving the character. Retry only
    // explicit platform identity claims; authored AI/non-human characters keep their form.
    private val RUNTIME_ALWAYS_OUT_OF_ROLE_PATTERNS = listOf(
        Regex("""(?i)(?:作为|身为|我是|我只是)\s*(?:一个|一名)?\s*(?:AI\s*)?(?:语言模型|大语言模型|ChatGPT|聊天机器人|虚拟助手|智能助手)"""),
        Regex("""(?i)\b(?:as an? (?:language model|virtual assistant)|i (?:am|'m) (?:an? )?(?:language model|virtual assistant))\b"""),
    )

    private val RUNTIME_EMBODIED_CHARACTER_PATTERNS = listOf(
        Regex("""(?i)(?:我是|我只是|作为|身为)\s*(?:一个|一名)?\s*(?:AI|人工智能)\b"""),
        Regex("""(?i)\b(?:as an? ai|i (?:am|'m) (?:an? )?ai)\b"""),
    )

    private val AUTHORED_AI_IDENTITY = Regex("""(?i)\bAI\b|人工智能|语言模型|聊天机器人|虚拟助手|智能助手|程序生命|数字生命|机器人|仿生人""")

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

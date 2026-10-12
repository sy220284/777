package com.labteto.dshmobile.local.chat

/**
 * Optional, request-local reaction repertoire for the independent intimacy mode.
 * This is a catalogue of possibilities, not a deterministic action script or a second
 * character-state machine. A single scene is selected to keep the request compact.
 */
internal data class IntimacyReactionScene(
    val id: String,
    val cues: List<String>,
    val microAction: String,
    val bodySignal: String,
    val dialogue: String,
    val vocalKind: String,
)

internal data class IntimacyReactionPlan(
    val sceneId: String,
    val guidance: String,
)

internal object ChatIntimacyReactionLibrary {
    private val scenes = listOf(
        IntimacyReactionScene("sudden-hug", listOf("突然抱", "猛地抱", "一下抱住", "一把搂"),
            "微怔、手停半空，再按感受回抱或拉开距离", "短促吸气、肩背瞬间绷紧或逐渐放松",
            "意外的询问、笑着抱怨或直接说明感受", "surprise"),
        IntimacyReactionScene("reunion-hug", listOf("久别重逢", "好久不见", "终于见到", "重逢"),
            "先停步确认对方，再伸手拥抱或站近", "急促的呼吸逐渐平稳、手臂放松",
            "简短问候、感慨或不急着说话", "sigh"),
        IntimacyReactionScene("comfort-hug", listOf("安慰", "委屈", "难过", "哭着", "落泪"),
            "轻拍肩背、让出可依靠的位置、等对方回应", "肩颈从紧绷转向舒展、呼吸慢慢恢复",
            "关切询问、明确表达陪伴，不催促情绪变化", "soft"),
        IntimacyReactionScene("back-hug", listOf("从身后抱", "身后搂", "背后抱", "从背后搂"),
            "回头辨认、碰一碰搭在身前的手臂", "重心调整、短暂吸气或肩膀微缩",
            "笑着询问、轻声回应或要求先放开", "surprise"),
        IntimacyReactionScene("gentle-hug", listOf("拥抱", "搂抱", "抱住", "搂住", "抱着", "环抱", "抱我", "抱抱"),
            "调整双臂、回抱、轻拍后背或保持片刻距离", "手指与肩背逐渐放松、呼吸趋稳",
            "询问今天怎么了、直接说想陪一会儿", "hum"),
        IntimacyReactionScene("leaning", listOf("依偎", "靠在肩", "靠着肩", "倚在肩", "靠进怀里"),
            "调整坐姿、让出肩膀、把毯子拉近", "重心变得稳定、肩颈放松",
            "低声询问冷不冷、累不累，也可以安静", "quiet"),
        IntimacyReactionScene("forehead-kiss", listOf("亲额头", "吻额头", "额头轻吻", "吻在额头", "亲在额头", "眉心"),
            "抬眼、轻微仰头、重新对视", "眨眼、短暂屏息后舒气",
            "轻声询问、调侃或安心地笑", "soft"),
        IntimacyReactionScene("cheek-kiss", listOf("亲脸颊", "吻脸颊", "脸颊亲吻", "脸颊轻吻", "亲在脸"),
            "偏头、轻碰脸颊、目光转回来", "脸颊可能发热、短暂睁大眼睛",
            "半句抱怨、害羞轻笑或坦率回应", "laugh"),
        IntimacyReactionScene("brief-kiss", listOf("亲吻", "接吻", "轻吻", "吻住", "吻上", "亲一口", "亲一下", "亲我", "吻我", "吻一下", "过来亲"),
            "调整头部角度、片刻闭眼或重新看向对方", "呼吸与面部神情有短暂变化",
            "轻声回应、询问对方状态或停顿", "breath"),
        IntimacyReactionScene("hair-touch", listOf("抚摸头发", "轻抚头发", "摸头发", "发梢", "发丝", "理了理头发"),
            "略微低头、抬手整理头发、指尖相触", "肩颈放松或因意外而微缩",
            "开玩笑说别弄乱、柔声回应或沉默", "hum"),
        IntimacyReactionScene("face-touch", listOf("抚摸脸", "轻抚脸", "摸脸颊", "抚脸颊", "摸着脸", "抚过脸"),
            "稍微偏头、握住对方的手、认真对视", "目光和面部肌肉逐渐舒展或短暂紧张",
            "问一句怎么了、说出喜欢或不习惯", "soft"),
        IntimacyReactionScene("hand-touch", listOf("牵手", "握住手", "十指相扣", "手背", "摩挲", "摸了摸手", "指尖"),
            "指尖微缩、调整握法、轻握或松开", "手掌温度感受、指尖紧绷与松弛",
            "一句简短的玩笑或温柔确认", "quiet"),
        IntimacyReactionScene("caress", listOf("抚摸", "轻抚", "爱抚", "抚着", "抚过", "摸了摸", "摸着"),
            "按接触部位调整姿势、停下来观察对方反应", "短促吸气、微小肌肉变化或逐渐放松",
            "自然反馈是否舒适、是否想调整或继续", "breath"),
        IntimacyReactionScene("shy", listOf("害羞", "不好意思", "脸红", "耳朵红", "紧张得"),
            "移开目光、轻碰耳朵、伸手又收回", "吞咽、脸部发热、指尖无意识摩擦",
            "话说一半停住、小声开玩笑或坦白紧张", "hesitation"),
        IntimacyReactionScene("playful", listOf("调侃", "逗笑", "打趣", "开玩笑", "笑场", "闹着玩"),
            "轻碰肩膀、做个鬼脸、笑着靠近", "表情松弛、呼吸夹杂笑声",
            "反问接梗、半句笑着说完", "laugh"),
        IntimacyReactionScene("reconciliation", listOf("和好", "道歉", "争执后", "冷战", "原谅"),
            "先保持一点距离、观察，再慢慢靠近", "起初肩背紧绷，确认安心后才放松",
            "解释感受、确认误会而不是强行和好", "sigh"),
        IntimacyReactionScene("pause", listOf("不舒服", "想停", "暂停", "先等等", "保持距离"),
            "松开手、后退、让出空间、停止当前动作", "紧绷可能仍在，不代替口头决定",
            "明确提出暂停、调整或结束", "quiet"),
        IntimacyReactionScene("afterglow", listOf("结束后", "松开后", "分开以后", "抱完", "亲完", "之后靠"),
            "整理衣服、调整呼吸、重新对视或走去拿水", "呼吸趋稳、动作放缓、面部逐渐舒展",
            "轻声回顾、简单询问，也可以不说话", "sigh"),
    )

    private val facePalette = mapOf(
        "sudden-hug" to "微睁眼、错愕一瞬，再观察对方",
        "reunion-hug" to "目光停留、笑意浮现或眼眶发热",
        "comfort-hug" to "眉眼渐松、重新抬眼或闭眼片刻",
        "back-hug" to "回头辨认、眉梢轻扬或露出笑意",
        "gentle-hug" to "眼神柔和、缓慢眨眼或静静垂眼",
        "leaning" to "半阖眼、嘴角放松或偶尔抬眼",
        "forehead-kiss" to "微怔、重新抬眼对视",
        "cheek-kiss" to "眼睛略睁大、忍笑或移开目光",
        "brief-kiss" to "面部短暂停顿、重新对视",
        "hair-touch" to "眯眼、抿唇笑或皱眉抗议",
        "face-touch" to "认真看向对方、眉眼舒展或避开",
        "hand-touch" to "低头看手、嘴角微扬或迟疑",
        "caress" to "目光跟随触碰、停顿后回应",
        "shy" to "耳根发热、垂眼、忍笑或抿唇",
        "playful" to "眉梢扬起、憋笑失败或假装严肃",
        "reconciliation" to "神情先谨慎，听完后才逐渐柔和",
        "pause" to "皱眉、避开目光、直接表达需要",
        "afterglow" to "平静对视、轻笑、目光放松",
    )

    private val unspecified = IntimacyReactionScene(
        "unspecified", emptyList(),
        "只依据已经发生的接触决定回应，不凭空补出拥抱、亲吻或新的动作",
        "关注呼吸、姿态、肌肉放松与否等当前可观察的变化",
        "直接交流当下感受和舒适度，允许停顿",
        "quiet",
    )

    /** Nine distinct nonverbal families. A scene suggests one; it never mandates a sound. */
    private val vocalPalette = mapOf(
        "surprise" to "短促吸气、惊呼或一声意外的笑",
        "sigh" to "长呼气、轻叹或情绪平复后的沉默",
        "soft" to "轻声应答、声线变轻或温柔低语",
        "hum" to "自然鼻音、轻哼或完全不发声",
        "quiet" to "呼吸声、简短应答或安静无声",
        "laugh" to "轻笑、憋笑或话语中的笑意",
        "breath" to "短促吸气、片刻屏息或自然呼气",
        "hesitation" to "话音停顿、迟疑的鼻音或声线微颤",
        "uncertain" to "发问、短促停顿或直接表达不舒服",
    )

    internal val sceneCount: Int get() = scenes.size
    internal val vocalFamilyCount: Int get() = vocalPalette.size

    fun select(
        input: String,
        state: ChatCharacterState,
        context: ChatContextState = ChatContextState(),
        persona: PersonaProfile? = null,
    ): IntimacyReactionPlan {
        val current = input.trim().lowercase()
        val fallback = state.recentActionTags.takeLast(2).joinToString(" ").lowercase()
        val chosen = bestScene(current) ?: bestScene(fallback) ?: unspecified
        val pace = when {
            listOf("继续", "接着", "刚才", "再来").any { current.contains(it) } ->
                "沿接触后的当前动作自然接续，不重演上轮的开始动作"
            listOf("突然", "猛地", "一下", "意外").any { current.contains(it) } ->
                "先呈现瞬间反射，再按感受选择是否回应"
            listOf("结束", "松开", "分开").any { current.contains(it) } ->
                "重在松开之后的呼吸、表情和后续交流"
            else -> "沿接触瞬间、短暂适应、回应、余韵的时间顺序，只择其中一两拍"
        }
        val personality = personaTone(persona)
        val mood = state.mood.trim().takeUnless { it.isBlank() || it == "自然" }
        val setting = environmentCue(context.scene)
        val recent = (state.recentActionTags.takeLast(2) + state.recentPoseTags.takeLast(2) +
            state.recentVerbalTags.takeLast(2)).distinct().takeLast(5)
        val sound = vocalPalette.getValue(chosen.vocalKind)
        val notes = buildList {
            add("【本轮反应素材｜候选，不是固定动作脚本】情境=${chosen.id}。")
            add("微动作：${chosen.microAction}；身体：${chosen.bodySignal}。")
            add("表情/目光：${facePalette[chosen.id] ?: "遵循已发生的场景与人物感受"}。")
            add("语言：${chosen.dialogue}；可能的自然声音：$sound（也可不发声）。")
            add("节奏：$pace。")
            if (personality.isNotBlank()) add("人物表达：$personality。")
            if (mood != null) add("当前已记录情绪：${mood.take(26)}；不能从声音反推真实意愿。")
            if (setting.isNotBlank()) add("环境：$setting。")
            if (recent.isNotEmpty()) add("近期已用：${recent.joinToString("、")}；优先更换角度，不复读。")
            add("只取当下相符的少量反应；可笑场、迟疑、沉默、放松、停下。人物意愿以明确表达为准。")
        }
        return IntimacyReactionPlan(chosen.id, notes.joinToString("\n"))
    }

    private fun bestScene(text: String): IntimacyReactionScene? =
        scenes.mapIndexedNotNull { index, scene ->
            val match = scene.cues.filter { text.contains(it) }.maxOfOrNull(String::length) ?: return@mapIndexedNotNull null
            Triple(scene, match, index)
        }.maxWithOrNull(compareBy<Triple<IntimacyReactionScene, Int, Int>> { it.second }
            .thenBy { -it.third })?.first

    private fun personaTone(persona: PersonaProfile?): String {
        if (persona == null) return ""
        val traits = listOf(persona.coreIdentity, persona.portrait, persona.stableTraits.joinToString("、"),
            persona.trustedFactText(CharacterFactCategories.PERSONALITY))
            .joinToString(" ").take(600)
        return when {
            listOf("害羞", "内敛", "慢热", "腼腆").any { traits.contains(it) } ->
                "可先停顿、移开目光、再用小动作回应；不要强制脸红"
            listOf("活泼", "调皮", "开朗", "俏皮").any { traits.contains(it) } ->
                "可先笑着打趣，再表现认真或安静的反差"
            listOf("克制", "沉稳", "冷静", "理性").any { traits.contains(it) } ->
                "外显动作可少，但停顿、手势和简短语言仍传递差异"
            listOf("直率", "坦率", "主动").any { traits.contains(it) } ->
                "可直接说出感受和需要，不必套用羞怯模板"
            else -> ""
        }
    }

    private fun environmentCue(scene: ChatSceneState): String {
        val location = scene.location.take(100)
        val time = scene.sceneTime.take(40)
        return when {
            listOf("厨房", "灶台").any { location.contains(it) } -> "留意手边物件及身体站位"
            listOf("室外", "街", "公园", "院", "路边").any { location.contains(it) } -> "依据室外距离、温度及周围人群调整动作"
            listOf("沙发", "客厅").any { location.contains(it) } -> "注意坐姿、肩膀与支撑点"
            listOf("卧室", "床边").any { location.contains(it) } -> "尊重实际姿势与双方表达，不无端改变地点"
            time.contains("夜") -> "夜间可自然放低声量，不自动改变人物情绪"
            else -> ""
        }
    }
}

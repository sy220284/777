package com.labteto.dshmobile.local.chat

internal object ChatRoleplayNoveltyScanner {
    fun tags(texts: List<String>): List<String> = texts.asSequence()
        .flatMap { tags(it).asSequence() }
        .distinct()
        .take(MAX_TAGS)
        .toList()

    fun tags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.lowercase()
        return ROLEPLAY_BEAT_HINTS.asSequence()
            .filter { (_, hints) -> hints.any(normalized::contains) }
            .map { it.key }
            .take(MAX_TAGS_PER_REPLY)
            .toList()
    }

    private val ROLEPLAY_BEAT_HINTS = linkedMapOf(
        "叹气" to listOf("叹了口气", "轻叹", "叹气"),
        "轻笑" to listOf("轻笑", "笑了一声", "弯了弯唇", "勾起嘴角"),
        "沉默停顿" to listOf("沉默片刻", "安静了一会", "停顿片刻", "没有立刻回答"),
        "看向别处" to listOf("看向窗外", "望向窗外", "移开视线", "别开脸", "看向一旁"),
        "低头" to listOf("低下头", "垂下眼", "垂眸", "低头看"),
        "点头" to listOf("点了点头", "轻轻点头", "点头"),
        "摇头" to listOf("摇了摇头", "轻轻摇头", "摇头"),
        "端起饮品" to listOf("端起茶", "端起杯", "拿起杯", "抿了口", "喝了一口"),
        "放下饮品" to listOf("放下茶", "放下杯", "把杯子放下"),
        "整理衣物" to listOf("整理衣领", "理了理衣领", "整理袖口", "理了理袖口"),
        "摸头" to listOf("摸了摸头", "揉了揉头", "揉揉头", "摸摸头"),
        "靠近" to listOf("靠近", "凑近", "走近", "挪近"),
        "后退" to listOf("退开", "后退一步", "往后退", "拉开距离"),
        "反问" to listOf("反问道", "挑眉反问", "不答反问"),
        "安慰" to listOf("别担心", "没事的", "我在", "慢慢来"),
        "追问" to listOf("怎么了", "为什么", "发生什么了", "你怎么想"),
        "重复解释" to listOf("我的意思是", "我只是想说", "换句话说", "简单来说"),
    )

    private const val MAX_TAGS_PER_REPLY = 6
    private const val MAX_TAGS = 12
}

package com.labteto.dshmobile.local.chat

import java.util.Calendar

private const val LIFE_BACKGROUND_TTL_MILLIS = 8L * 60L * 60L * 1_000L
private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

internal fun advanceCharacterLife(
    persona: PersonaProfile,
    state: ChatCharacterState,
    now: Long = System.currentTimeMillis(),
    storyTime: String = "",
): CharacterLifeState {
    val previous = state.lifeState
    val events = linkedMapOf<String, CharacterLifeEvent>()

    previous.activeEvents.asSequence()
        .filter { it.active && (it.expiresAt <= 0L || it.expiresAt > now) }
        .forEach { events[it.id] = it }

    fun upsert(source: String, summary: String, kind: CharacterLifeEventKind, ttlMillis: Long) {
        val clean = summary.trim().replace(Regex("\\s+"), " ").take(220)
        if (clean.isBlank()) return
        val id = "$source-${clean.hashCode().toUInt().toString(16)}"
        val old = previous.activeEvents.firstOrNull { it.id == id }
        val sourceAt = old?.updatedAt?.takeIf { it > 0L } ?: state.updatedAt.takeIf { it > 0L } ?: now
        val expiresAt = sourceAt + ttlMillis
        if (expiresAt <= now) return
        events[id] = CharacterLifeEvent(
            id = id,
            summary = clean,
            kind = kind,
            source = source,
            startedAt = old?.startedAt?.takeIf { it > 0L } ?: sourceAt,
            updatedAt = sourceAt,
            expiresAt = expiresAt,
            active = true,
        )
    }

    state.currentAgenda.takeIf(String::isNotBlank)?.let {
        upsert("agenda", it, CharacterLifeEventKind.ONGOING, 3L * DAY_MILLIS)
    }
    state.immediateConcern.takeIf(String::isNotBlank)?.let {
        upsert("concern", it, CharacterLifeEventKind.BACKGROUND, DAY_MILLIS)
    }
    state.unresolvedThreads.takeLast(3).forEach {
        upsert("unfinished", it, CharacterLifeEventKind.ONGOING, 7L * DAY_MILLIS)
    }

    val anchors = lifeAnchors(persona.factText(CharacterFactCategories.LIFE_GRAVITY).ifBlank { persona.lifeContext })
    val beat = selectLifeBeat(anchors, previous.currentBeat, now, storyTime)
    if (beat.isNotBlank()) {
        upsert("persona", beat, CharacterLifeEventKind.BACKGROUND, LIFE_BACKGROUND_TTL_MILLIS)
    }

    return CharacterLifeState(
        lastAdvancedAt = now,
        dayIndex = (now / DAY_MILLIS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        currentBeat = beat,
        activeEvents = events.values
            .sortedWith(compareByDescending<CharacterLifeEvent> { it.kind.ordinal }.thenByDescending { it.updatedAt })
            .take(8),
    )
}

internal fun renderCharacterLifePrompt(life: CharacterLifeState): String {
    if (life.currentBeat.isBlank() && life.activeEvents.isEmpty()) return ""
    return buildString {
        appendLine("【独立生活流】")
        life.currentBeat.takeIf(String::isNotBlank)?.let { appendLine("可能相关的生活习惯（不代表此刻已经发生）：${it.take(180)}") }
        life.activeEvents.asSequence()
            .filter { it.source != "persona" }
            .take(3)
            .forEach { appendLine("仍在继续：${it.summary}") }
        append(
            "这些内容只来自人物既有生活资料或已经发生的事情。它们可以影响注意力、语气和主动联系，" +
                "但不是必须说出口的设定展示，也不能据此补写新的重大人生事件。",
        )
    }.trim()
}

internal fun characterLifeProactiveReason(
    persona: PersonaProfile,
    state: ChatCharacterState,
    now: Long = System.currentTimeMillis(),
): String? {
    val life = advanceCharacterLife(persona, state, now)
    val grounded = life.activeEvents.asSequence()
        .filter { it.source != "persona" }
        .sortedByDescending { it.updatedAt }
        .firstOrNull()
        ?.summary
    return grounded ?: life.currentBeat.takeIf(String::isNotBlank)
}

private fun selectLifeBeat(
    anchors: List<String>,
    previousBeat: String,
    now: Long,
    storyTime: String,
): String {
    if (anchors.isEmpty()) return ""
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    val hour = when {
        storyTime.containsAnyLife("深夜", "凌晨") -> 2
        storyTime.containsAnyLife("晚上", "夜晚", "夜里") -> 20
        storyTime.containsAnyLife("下午") -> 15
        storyTime.containsAnyLife("中午", "午间") -> 12
        storyTime.containsAnyLife("早晨", "清晨", "早上", "上午") -> 8
        storyTime.isNotBlank() -> -1 // Unknown story clock must not borrow the phone clock.
        else -> calendar.get(Calendar.HOUR_OF_DAY)
    }
    val weekend = if (storyTime.isNotBlank()) storyTime.containsAnyLife("周末", "周六", "周日")
        else calendar.get(Calendar.DAY_OF_WEEK) in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
    val scored = anchors.mapIndexed { index, anchor ->
        Triple(anchor, temporalLifeScore(anchor, hour, weekend), index)
    }
    val bestScore = scored.maxOf(Triple<String, Int, Int>::second)
    if (bestScore > 0) {
        return scored.filter { it.second == bestScore }.minBy(Triple<String, Int, Int>::third).first
    }
    if (previousBeat in anchors) return previousBeat
    val day = (now / DAY_MILLIS).coerceAtLeast(0L)
    return anchors[(day % anchors.size).toInt()]
}

private fun temporalLifeScore(anchor: String, hour: Int, weekend: Boolean): Int {
    var score = 0
    if (weekend && anchor.containsAnyLife("周末", "周六", "周日", "星期六", "星期日")) score += 2
    if (!weekend && anchor.containsAnyLife("工作日", "平日")) score += 4
    if (hour in 5..10 && anchor.containsAnyLife("清晨", "早上", "上午", "早餐")) score += 8
    if (hour in 11..13 && anchor.containsAnyLife("中午", "午休", "午饭")) score += 8
    if (hour in 14..17 && anchor.containsAnyLife("下午")) score += 8
    if (hour in 18..23 && anchor.containsAnyLife("晚上", "夜里", "夜晚", "下班后")) score += 8
    if (hour in 0..4 && anchor.containsAnyLife("深夜", "凌晨", "夜里")) score += 8
    if (hour in 6..17 && anchor.contains("白天")) score += 6
    return score
}

private fun lifeAnchors(text: String): List<String> =
    text.split(Regex("[。！？!?；;\\n]+"))
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 4 }
        .map { it.take(180) }
        .distinct()
        .take(8)
        .toList()

private fun String.containsAnyLife(vararg values: String): Boolean = values.any(::contains)

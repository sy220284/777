package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.Json

fun interface SessionReducer<S> {
    fun reduce(state: S, event: SessionEvent): S
}

class SessionProjection<S>(
    private val initial: () -> S,
    private val reducer: SessionReducer<S>,
) {
    fun fold(events: Iterable<SessionEvent>): S =
        events.fold(initial()) { state, event -> reducer.reduce(state, event) }
}

class SessionQuery(
    private val events: List<SessionEvent>,
) {
    fun byType(type: String): List<SessionEvent> = events.filter { it.type == type }

    fun search(text: String, json: Json): List<SessionEvent> {
        require(text.isNotBlank()) { "搜索内容不能为空" }
        return events.filter { event ->
            event.type.contains(text, ignoreCase = true) ||
                json.encodeToString(SessionEvent.serializer(), event).contains(text, ignoreCase = true)
        }
    }

    fun around(sequence: Long, before: Int = 1, after: Int = 1): List<SessionEvent> {
        val index = events.indexOfFirst { it.sequence == sequence }
        if (index < 0) return emptyList()
        val from = (index - before.coerceAtLeast(0)).coerceAtLeast(0)
        val to = (index + after.coerceAtLeast(0) + 1).coerceAtMost(events.size)
        return events.subList(from, to)
    }
}

object SessionExport {
    fun jsonLines(events: Iterable<SessionEvent>, json: Json): String =
        events.joinToString("\n") { json.encodeToString(SessionEvent.serializer(), it) }
            .let { if (it.isEmpty()) it else "$it\n" }
}

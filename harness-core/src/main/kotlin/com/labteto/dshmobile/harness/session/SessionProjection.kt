package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.Json

fun interface SessionReducer<S> {
    fun reduce(state: S, event: SessionEvent): S
}

data class SessionProjectionSnapshot<S>(
    val state: S,
    val asOfSequence: Long,
    val stateVersion: Int,
)

class SessionProjection<S>(
    private val initial: () -> S,
    private val reducer: SessionReducer<S>,
    val stateVersion: Int = 1,
) {
    init {
        require(stateVersion >= 1) { "会话投影状态版本必须大于 0" }
    }

    fun fold(events: Iterable<SessionEvent>): S =
        foldSnapshot(events).state

    fun foldSnapshot(events: Iterable<SessionEvent>): SessionProjectionSnapshot<S> {
        var state = initial()
        var asOfSequence = -1L
        events.forEach { event ->
            require(event.sequence > asOfSequence) {
                "会话投影事件必须按 sequence 严格递增：上一事件=$asOfSequence，当前事件=${event.sequence}"
            }
            state = reducer.reduce(state, event)
            asOfSequence = event.sequence
        }
        return SessionProjectionSnapshot(
            state = state,
            asOfSequence = asOfSequence,
            stateVersion = stateVersion,
        )
    }
}

/**
 * Explicit-composition registry for reusable Session projections.
 *
 * Registration returns a typed handle instead of exposing an untyped name lookup. Feature owners
 * keep that handle and therefore retain compile-time state ownership while the shared registry
 * enforces globally unique projection names and state-version metadata.
 */
class SessionProjectionRegistry {
    private val lock = Any()
    private val registeredNames = linkedSetOf<String>()

    fun <S> register(
        name: String,
        stateVersion: Int = 1,
        initial: () -> S,
        reducer: SessionReducer<S>,
    ): RegisteredSessionProjection<S> {
        val canonicalName = name.trim()
        require(canonicalName.isNotEmpty()) { "会话投影名称不能为空" }
        require(stateVersion >= 1) { "会话投影状态版本必须大于 0" }
        synchronized(lock) {
            require(registeredNames.add(canonicalName)) {
                "会话投影名称重复：$canonicalName"
            }
        }
        return RegisteredSessionProjection(
            name = canonicalName,
            projection = SessionProjection(
                initial = initial,
                reducer = reducer,
                stateVersion = stateVersion,
            ),
        )
    }

    fun names(): List<String> = synchronized(lock) { registeredNames.toList() }
}

class RegisteredSessionProjection<S> internal constructor(
    val name: String,
    private val projection: SessionProjection<S>,
) {
    val stateVersion: Int get() = projection.stateVersion

    fun fold(events: Iterable<SessionEvent>): SessionProjectionSnapshot<S> =
        projection.foldSnapshot(events)
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

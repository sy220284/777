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
        require(stateVersion >= 0) { "会话投影状态版本必须是非负整数" }
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
data class SessionProjectionRegistrySnapshot(
    val values: Map<String, Any?>,
    val asOfSequence: Long,
    val stateVersions: Map<String, Int>,
)

/**
 * Explicit-composition registry for reusable Session projections.
 *
 * Same-name registrations at the same state version share the first registered unit and are
 * reference-counted, matching the pinned official Harness contract. A different state version is
 * rejected rather than silently mixing incompatible persisted semantics. Feature owners still keep
 * typed handles; the registry-level snapshot is an untyped carrier view over one exact event cut.
 */
class SessionProjectionRegistry {
    private data class Registration(
        val projection: SessionProjection<*>,
        val stateVersion: Int,
        var refs: Int,
    )

    private val lock = Any()
    private val registrations = linkedMapOf<String, Registration>()

    fun <S> register(
        name: String,
        stateVersion: Int = 1,
        initial: () -> S,
        reducer: SessionReducer<S>,
    ): RegisteredSessionProjection<S> {
        val canonicalName = name.trim()
        require(canonicalName.isNotEmpty()) { "会话投影名称不能为空" }
        require(stateVersion >= 0) { "会话投影状态版本必须是非负整数" }

        val registration = synchronized(lock) {
            val existing = registrations[canonicalName]
            if (existing == null) {
                Registration(
                    projection = SessionProjection(
                        initial = initial,
                        reducer = reducer,
                        stateVersion = stateVersion,
                    ),
                    stateVersion = stateVersion,
                    refs = 1,
                ).also { registrations[canonicalName] = it }
            } else {
                require(existing.stateVersion == stateVersion) {
                    "会话投影 $canonicalName 已以 stateVersion=${existing.stateVersion} 注册，" +
                        "拒绝共享 stateVersion=$stateVersion"
                }
                existing.refs += 1
                existing
            }
        }

        @Suppress("UNCHECKED_CAST")
        val projection = registration.projection as SessionProjection<S>
        return RegisteredSessionProjection(
            name = canonicalName,
            projection = projection,
            onDispose = { release(canonicalName, registration) },
        )
    }

    fun names(): List<String> = synchronized(lock) { registrations.keys.toList() }

    /**
     * Fold every currently registered projection over one materialized event list and return one
     * consistent cut. The materialization is intentional: every unit must observe the identical
     * prefix even when the caller supplied a one-shot Iterable.
     */
    fun foldSnapshot(events: Iterable<SessionEvent>): SessionProjectionRegistrySnapshot {
        val eventCut = events.toList()
        val current = synchronized(lock) { registrations.toMap() }
        val values = linkedMapOf<String, Any?>()
        val versions = linkedMapOf<String, Int>()
        var sharedWatermark: Long? = null

        current.forEach { (name, registration) ->
            @Suppress("UNCHECKED_CAST")
            val projection = registration.projection as SessionProjection<Any?>
            val snapshot = projection.foldSnapshot(eventCut)
            val watermark = sharedWatermark
            if (watermark == null) {
                sharedWatermark = snapshot.asOfSequence
            } else {
                check(watermark == snapshot.asOfSequence) {
                    "会话投影一致切面撕裂：$name=${snapshot.asOfSequence}，期望=$watermark"
                }
            }
            values[name] = snapshot.state
            versions[name] = snapshot.stateVersion
        }

        val asOfSequence = sharedWatermark
            ?: eventCut.lastOrNull()?.sequence
            ?: -1L
        return SessionProjectionRegistrySnapshot(
            values = values,
            asOfSequence = asOfSequence,
            stateVersions = versions,
        )
    }

    private fun release(
        name: String,
        registration: Registration,
    ) {
        synchronized(lock) {
            val live = registrations[name]
            if (live !== registration) return
            live.refs -= 1
            if (live.refs <= 0) {
                registrations.remove(name)
            }
        }
    }
}

class RegisteredSessionProjection<S> internal constructor(
    val name: String,
    private val projection: SessionProjection<S>,
    private val onDispose: () -> Unit = {},
) : AutoCloseable {
    private var disposed = false

    val stateVersion: Int get() = projection.stateVersion

    fun fold(events: Iterable<SessionEvent>): SessionProjectionSnapshot<S> =
        projection.foldSnapshot(events)

    override fun close() {
        if (disposed) return
        disposed = true
        onDispose()
    }

    fun dispose() = close()
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

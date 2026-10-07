package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.RegisteredSessionProjection
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionSnapshot
import com.labteto.dshmobile.harness.session.SessionReducer
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import javax.inject.Inject
import javax.inject.Singleton

internal const val LOCAL_STRUCTURED_EVENT_SCAN_LIMIT = 160

internal data class LocalWorkStructuredEventWindow(
    val events: List<SessionEvent> = emptyList(),
)

/** Work-owned typed projection registered in the shared Session projection registry. */
@Singleton
internal class LocalWorkSessionProjectionRuntime @Inject constructor(
    sessionStorage: LocalSessionStorageRuntime,
) {
    private val structuredEventWindow: RegisteredSessionProjection<LocalWorkStructuredEventWindow> =
        sessionStorage.projectionRegistry.register(
            name = "work.structured-event-window",
            stateVersion = 1,
            initial = { LocalWorkStructuredEventWindow() },
            reducer = SessionReducer { state, event ->
                state.copy(
                    events = (state.events + event).takeLast(LOCAL_STRUCTURED_EVENT_SCAN_LIMIT),
                )
            },
        )

    internal fun structuredEventSnapshot(
        eventLog: LocalSessionEventLog,
    ): SessionProjectionSnapshot<LocalWorkStructuredEventWindow> =
        structuredEventWindow.fold(localWorkStructuredProjectionEvents(eventLog))
}

internal fun localWorkStructuredProjectionEvents(
    eventLog: LocalSessionEventLog,
): List<SessionEvent> =
    eventLog.pageBefore(limit = LOCAL_STRUCTURED_EVENT_SCAN_LIMIT)
        .map { event ->
            SessionEvent(
                sequence = event.sequence,
                type = event.type,
                createdAt = event.createdAt,
                data = event.data,
            )
        }

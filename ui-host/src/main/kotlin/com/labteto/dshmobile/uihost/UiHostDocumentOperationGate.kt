package com.labteto.dshmobile.uihost

/**
 * One Host activity-result workflow owns one user request through the picker AND
 * the asynchronous provider write. Clearing state when the picker returns is unsafe:
 * it lets a second export overwrite the first operation before the bytes are copied.
 *
 * All transitions are invoked on the main thread by the Host. A stale callback is
 * rejected rather than attaching bytes to another request after cancellation.
 */
class UiHostDocumentOperationGate<T> {
    enum class Phase { IDLE, WAITING_FOR_PICKER, TRANSFERRING }
    data class Ticket<T>(val generation: Long, val request: T)

    private var generation = 0L
    private var current: Ticket<T>? = null
    var phase: Phase = Phase.IDLE
        private set

    fun begin(request: T): Ticket<T>? {
        if (phase != Phase.IDLE) return null
        val ticket = Ticket(++generation, request)
        current = ticket
        phase = Phase.WAITING_FOR_PICKER
        return ticket
    }

    fun selectionReturned(): Ticket<T>? {
        if (phase != Phase.WAITING_FOR_PICKER) return null
        phase = Phase.TRANSFERRING
        return current
    }

    fun finish(ticket: Ticket<T>): Boolean {
        if (current != ticket) return false
        current = null
        phase = Phase.IDLE
        return true
    }

    fun cancelSelection(): Ticket<T>? {
        if (phase != Phase.WAITING_FOR_PICKER) return null
        return current?.also { finish(it) }
    }

    /** A launch exception is a failed admission, not a fabricated picker cancellation. */
    fun failToLaunch(ticket: Ticket<T>): Boolean =
        phase == Phase.WAITING_FOR_PICKER && finish(ticket)
}

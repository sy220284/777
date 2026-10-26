package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import kotlinx.coroutines.CancellationException

/** Only a restored, identified Work session may query its durable event cursor. */
internal fun shouldReadWorkToolStatus(
    mode: LocalUsageMode,
    loading: Boolean,
    sessionId: String,
): Boolean = mode == LocalUsageMode.WORK && !loading && sessionId.isNotBlank()

internal sealed interface LocalWorkToolActivityRead {
    data class Available(val activities: List<LocalToolActivityUiItem>) : LocalWorkToolActivityRead
    data class Failed(val cause: Exception) : LocalWorkToolActivityRead
}

/** Read cursor only. Tool state and all execution remain owned by the existing event projection. */
internal class LocalWorkToolActivityReader(
    private val sequence: () -> Long,
    private val activities: () -> List<LocalToolActivityUiItem>,
) {
    private var lastSequence: Long? = null

    fun read(): LocalWorkToolActivityRead? = try {
        val current = sequence()
        if (current == lastSequence) null else {
            val next = if (current < 0L) emptyList() else activities()
            lastSequence = current
            LocalWorkToolActivityRead.Available(next)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        lastSequence = null
        LocalWorkToolActivityRead.Failed(failure)
    }
}

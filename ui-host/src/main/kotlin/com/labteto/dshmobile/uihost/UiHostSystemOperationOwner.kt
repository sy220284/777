package com.labteto.dshmobile.uihost

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * Host-owned cross-feature ActivityResult transaction coordinator.
 *
 * SAF picker callbacks, document reads, exports and workspace shares are one
 * platform operation domain. Separate Scene-level Boolean flags cannot prevent
 * one ActivityResult operation racing another with different source bytes.
 *
 * The host only serializes Android system interaction; Files/Chat/Work remain
 * the sole owners of the bytes, URI grants and business commands they submit.
 */
enum class UiHostPlatformAction {
    PICK_ATTACHMENT, IMPORT_DOCUMENT, SAVE_DOCUMENT, EXPORT_WORKSPACE, SHARE_WORKSPACE
}

class UiHostSystemOperationOwner {
    class Ticket internal constructor(
        val generation: Long,
        val action: UiHostPlatformAction,
    )

    private var nextGeneration = 0L
    private var active: Ticket? = null

    @Synchronized
    fun begin(action: UiHostPlatformAction): Ticket? {
        if (active != null) return null
        return Ticket(++nextGeneration, action).also { active = it }
    }

    @Synchronized
    fun finish(ticket: Ticket): Boolean {
        if (active !== ticket) return false
        active = null
        return true
    }

    @Synchronized
    fun activeAction(): UiHostPlatformAction? = active?.action
}

/** A null provider means standalone component tests; no implicit global singleton. */
val LocalUiHostSystemOperationOwner =
    compositionLocalOf<UiHostSystemOperationOwner?> { null }

@Composable
fun ProvideUiHostSystemOperationOwner(content: @Composable () -> Unit) {
    val owner = remember { UiHostSystemOperationOwner() }
    CompositionLocalProvider(LocalUiHostSystemOperationOwner provides owner) {
        content()
    }
}

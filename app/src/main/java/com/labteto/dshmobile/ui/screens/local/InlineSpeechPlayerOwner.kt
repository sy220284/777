package com.labteto.dshmobile.ui.screens.local

import android.media.MediaPlayer

/**
 * One file-bound owner for the async MediaPlayer lifecycle. The Compose key and
 * disposal key must both be the same absolute audio path. A late callback from A
 * must not mutate B or a newly-composed A, or resurrect its released player.
 */
internal class InlineSpeechPlayerOwner {
    private var active: MediaPlayer? = null
    private var disposed = false

    fun attach(player: MediaPlayer) {
        check(!disposed) { "Audio owner has been disposed" }
        if (active !== player) {
            active?.let { runCatching { it.release() } }
            active = player
        }
    }

    fun owns(player: MediaPlayer): Boolean = !disposed && active === player

    fun releaseIfOwned(player: MediaPlayer) {
        if (active !== player) return
        active = null
        runCatching { player.release() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        active?.let { releaseIfOwned(it) }
    }
}

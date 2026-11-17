package com.labteto.dshmobile.ui.screens.local

import kotlin.math.floor

/** Visible editor lines only; text remains scrollable and is never truncated. */
internal fun localComposerMaxLines(availableHeightDp: Float, lineHeightDp: Float): Int =
    floor(availableHeightDp * .4f / lineHeightDp).toInt().coerceIn(1, 6)

package com.labteto.dshmobile.local.presentation

import android.content.Context
import com.labteto.dshmobile.local.work.LocalWorkSummarySettings

/** Existing settings page's boundary to Work-owned summary preferences. */
internal object LocalWorkSummaryControls {
    val state get() = LocalWorkSummarySettings.state
    fun attach(context: Context) = LocalWorkSummarySettings.attach(context)
    fun setEnabled(enabled: Boolean) = LocalWorkSummarySettings.setEnabled(enabled)
}

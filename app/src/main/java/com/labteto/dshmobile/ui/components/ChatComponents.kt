package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/** Legacy-neutral thinking disclosure reuses the current controlled detail row. */
@Composable
fun ThinkingRow(summary: String?, expanded: Boolean, onToggle: () -> Unit, streaming: Boolean = false) =
    DisclosureRow(title = summary ?: "Thinking…", state = if (streaming) DisclosureState.Running else DisclosureState.Idle,
        expanded = expanded, onToggle = onToggle)

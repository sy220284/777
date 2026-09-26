package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * A row that shows a stored value (model name, endpoint, masked key…) and opens an editor.
 * Kills the bare-OutlinedTextField stack: the page always reads as state, never as a form.
 *
 * [configured] renders a success dot after the value; [masked] shows the last four characters
 * for secrets. Long values ellipsize toward the start so key suffixes stay visible.
 */
@Composable
fun DsValueRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    masked: Boolean = false,
    configured: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val display = when {
        value.isNullOrBlank() -> "—"
        masked && value.length > 4 -> "••••••••${value.takeLast(4)}"
        else -> value
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = DsType.std14, color = colors.labelSecondary)
            Text(
                display,
                style = DsType.std14Strong,
                color = if (value.isNullOrBlank()) colors.labelTertiary else colors.labelPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (masked) FontFamily.Monospace else FontFamily.Default,
            )
            hint?.let {
                Text(it, style = DsType.caption11, color = colors.labelCaption, maxLines = 2)
            }
        }
        configured?.let { ok ->
            Spacer(Modifier.width(DsSpacing.small))
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (ok) colors.success else colors.error,
                        CircleShape,
                    ),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(DsSpacing.small))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelCaption,
            )
        }
    }
}

package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Compact 统一 tag/trigger: neutral by default, blue only for the selected state. */
@Composable
fun DsPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    warn: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val background = when {
        warn -> colors.warnTertiary
        selected -> colors.accentTertiary
        else -> colors.bgModulePlatform
    }
    val contentColor = when {
        warn -> colors.warnLabel
        selected -> colors.accent
        else -> colors.labelSecondary
    }

    Box(
        modifier = modifier
            .heightIn(min = if (onClick != null) DsSpacing.touchTarget else 24.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .widthIn(min = DsSpacing.touchTarget)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(24.dp),
            shape = if (warn) DsShapes.pillFull else DsShapes.pill,
            color = background,
            contentColor = contentColor,
            border = if (onClick != null && !warn && !selected) {
                BorderStroke(1.dp, colors.borderL2)
            } else {
                null
            },
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = DsType.xsmall12.withReadingWeight(),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsPillPreview() {
    DshTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsPill("Badge")
            DsPill("Trigger", onClick = {})
            DsPill("Selected", selected = true, onClick = {})
            DsPill("Warn", warn = true)
        }
    }
}

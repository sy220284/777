package com.shenyan.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors

/**
 * V5 solid top chrome. Business navigation and permissions stay caller-owned.
 * The start/end slots have equal touch targets so the title remains genuinely centered.
 */
@Composable
fun SyTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    backIcon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    largeTitle: Boolean = false,
    actionIcon: ImageVector? = null,
    actionPainter: Painter? = null,
    actionContentDescription: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val tokens = LocalSyColors.current
    SyMaterialSurface(
        modifier = modifier.fillMaxWidth(),
        tier = SyMaterialTier.L2_BAR,
        shape = androidx.compose.ui.graphics.RectangleShape, shadowElevation = 0.dp,
        border = BorderStroke(1.dp, tokens.border.copy(alpha = .45f)),
    ) {
        Column {
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = if (LocalConfiguration.current.screenWidthDp >= 600) 64.dp else 56.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(backIcon, contentDescription = backContentDescription, tint = tokens.text,
                    modifier = Modifier.size(24.dp))
            }
            Column(
                Modifier.weight(1f).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth(),
                    style = if (largeTitle) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if ((actionIcon != null || actionPainter != null) && onAction != null) {
                IconButton(onClick = onAction, enabled = actionEnabled,
                    modifier = Modifier.size(48.dp)) {
                    if (actionPainter != null) {
                        Icon(actionPainter, contentDescription = actionContentDescription,
                            tint = tokens.text, modifier = Modifier.size(24.dp))
                    } else if (actionIcon != null) {
                        Icon(actionIcon, contentDescription = actionContentDescription,
                            tint = tokens.text, modifier = Modifier.size(24.dp))
                    }
                }
            } else {
                Box(Modifier.size(48.dp))
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        }
    }
}

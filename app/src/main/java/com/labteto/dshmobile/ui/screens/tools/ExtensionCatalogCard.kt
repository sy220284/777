package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/**
 * A common Codex-style extension tile: provenance, installed state and readiness are separate.
 * It never labels an unverified MCP connection as "ready".
 */
@Composable
internal fun ExtensionCatalogCard(
    id: String,
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    documentIcon: Int? = null,
    actionText: String,
    enabled: Boolean = true,
    tagPrefix: String = "plugin-inventory",
    sourceLabel: String = "",
    statusLabel: String = "",
    attentionNeeded: Boolean = false,
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    val colors = LocalSyColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tagPrefix + "-card-" + id),
        onClick = onOpen,
        color = colors.materialL4,
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp).testTag(tagPrefix + "-icon-" + id),
                    color = accent.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (documentIcon != null) {
                            Image(
                                painter = painterResource(documentIcon), contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                        } else {
                            Icon(icon, contentDescription = null, tint = accent,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        title,
                        modifier = Modifier.fillMaxWidth().testTag(tagPrefix + "-title-" + id),
                        style = DsType.std14Strong.withReadingWeight(), color = colors.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (sourceLabel.isNotBlank()) {
                        Text(sourceLabel, style = DsType.caption11.withReadingWeight(),
                            color = colors.textTertiary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        description,
                        modifier = Modifier.fillMaxWidth().testTag(tagPrefix + "-detail-" + id),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                SyButton(
                    enabled = enabled, text = actionText,
                    variant = if (actionText == "安装") SyButtonVariant.Info else SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                    modifier = Modifier.testTag(tagPrefix + "-action-" + id),
                    onClick = onAction,
                )
            }
            if (statusLabel.isNotBlank()) {
                Surface(
                    color = if (attentionNeeded) colors.warning.copy(alpha = 0.14f)
                            else colors.mint.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        statusLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = DsType.caption11Strong.withReadingWeight(),
                        color = if (attentionNeeded) colors.warningInk else colors.successInk,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

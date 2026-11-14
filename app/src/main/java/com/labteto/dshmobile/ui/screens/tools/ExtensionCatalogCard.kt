package com.labteto.dshmobile.ui.screens.tools

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

/** One visual component for local plugins, skills and live connector records. */
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
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    val colors = LocalSyColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tagPrefix + "-card-" + id),
        onClick = onOpen,
        color = colors.materialL2,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Surface(
                modifier = Modifier.size(48.dp).testTag(tagPrefix + "-icon-" + id),
                color = accent.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (documentIcon != null) {
                        Image(
                            painter = painterResource(documentIcon),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().testTag(tagPrefix + "-title-" + id),
                    color = colors.text,
                    style = DsType.std14Strong.withReadingWeight(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    modifier = Modifier.fillMaxWidth().testTag(tagPrefix + "-detail-" + id),
                    color = colors.textSecondary,
                    style = DsType.small13.withReadingWeight(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SyButton(
                enabled = enabled,
                text = actionText,
                variant = SyButtonVariant.Info,
                size = SyButtonSize.Small,
                modifier = Modifier.testTag(tagPrefix + "-action-" + id),
                onClick = onAction,
            )
        }
    }
}

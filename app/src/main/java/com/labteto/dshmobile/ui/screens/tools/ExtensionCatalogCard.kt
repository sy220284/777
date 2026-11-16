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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors

/** One selectable marketplace row. All actions belong to the detail destination.
 * This is the sole row implementation; it does not retain a second card/action mode.
 */
@Composable
internal fun ExtensionCatalogCard(
    id: String,
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    documentIcon: Int? = null,
    brandLogo: ImageBitmap? = null,
    statusLabel: String = "",
    enabled: Boolean = true,
    tagPrefix: String = "plugin-inventory",
    onOpen: () -> Unit,
) {
    val colors = LocalSyColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-card-$id"),
        onClick = onOpen,
        enabled = enabled,
        color = colors.materialL4,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp).testTag("$tagPrefix-icon-$id"),
                color = if (documentIcon == null && brandLogo == null)
                    accent.copy(alpha = 0.09f) else colors.materialL4,
                shape = RoundedCornerShape(15.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        brandLogo != null -> Image(
                            bitmap = brandLogo, contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        documentIcon != null -> Image(
                            painter = painterResource(documentIcon), contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        else -> Icon(
                            icon, contentDescription = null, tint = accent,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-title-$id"),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.text,
                )
                Text(
                    description,
                    modifier = Modifier.testTag("$tagPrefix-detail-$id"),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (statusLabel.isNotBlank()) {
                    Text(
                        statusLabel,
                        modifier = Modifier.testTag("$tagPrefix-status-$id"),
                        style = DsType.caption11.withReadingWeight(),
                        color = when {
                            statusLabel.contains("失败") || statusLabel.contains("异常") ||
                                statusLabel.contains("冲突") -> colors.dangerInk
                            statusLabel.contains("待") || statusLabel.contains("需要") ||
                                statusLabel.contains("不可") -> colors.warningInk
                            statusLabel.contains("已安装") || statusLabel.contains("已连接") ||
                                statusLabel.contains("已内置") -> colors.successInk
                            else -> colors.textSecondary
                        },
                    )
                }
            }
            Icon(
                FeatherIcons.ChevronRight, contentDescription = "查看详情",
                tint = colors.textTertiary, modifier = Modifier.size(18.dp).align(Alignment.CenterVertically),
            )
        }
    }
}

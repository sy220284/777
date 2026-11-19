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
import com.shenyan.designsystem.components.SyFlatSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.syReadableInk
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.components.SyMaterialSurface
import com.shenyan.designsystem.components.SyMaterialTier

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
    marketState: ExtensionMarketState? = null,
    enabled: Boolean = true,
    tagPrefix: String = "plugin-inventory",
    onOpen: () -> Unit,
) {
    val colors = LocalSyColors.current
    val tile = if (documentIcon == null && brandLogo == null) lerp(colors.materialL4, accent, .10f)
        else colors.materialL4
    SyMaterialSurface(
        modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-card-$id"),
        onClick = onOpen,
        enabled = enabled,
        tier = SyMaterialTier.L3_CARD,
        shape = RoundedCornerShape(SyMetric.radiusCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyFlatSurface(
                modifier = Modifier.size(42.dp).testTag("$tagPrefix-icon-$id"),
                color = tile,
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        brandLogo != null -> Image(
                            bitmap = brandLogo, contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                        documentIcon != null -> Image(
                            painter = painterResource(documentIcon), contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                        else -> Icon(
                            icon, contentDescription = null, tint = syReadableInk(accent, tile, 3f),
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-title-$id"),
                    style = SyType.std14Strong.withReadingWeight(),
                    color = colors.text,
                )
                Text(
                    description,
                    modifier = Modifier.testTag("$tagPrefix-detail-$id"),
                    style = SyType.small13.withReadingWeight(),
                    color = colors.textSecondary,
                    maxLines = if (LocalDensity.current.fontScale >= 1.2f) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                marketState?.let { state ->
                    Text(
                        state.label,
                        modifier = Modifier.testTag("$tagPrefix-status-$id"),
                        style = SyType.caption11.withReadingWeight(),
                        color = when (state.status) {
                            ExtensionMarketStatus.FAILED -> colors.dangerInk
                            ExtensionMarketStatus.NEEDS_SETUP,
                            ExtensionMarketStatus.UNSUPPORTED,
                            ExtensionMarketStatus.REGISTERED -> colors.warningInk
                            ExtensionMarketStatus.INSTALLED -> colors.successInk
                            ExtensionMarketStatus.BUILTIN -> colors.textSecondary
                            ExtensionMarketStatus.INSTALLABLE,
                            ExtensionMarketStatus.MANUAL_ONLY,
                            ExtensionMarketStatus.CONNECTING -> colors.textSecondary
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

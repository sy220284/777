package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Semantic state of an automation task / run. */
enum class DsStatus { Running, Done, Failed, Neutral }

/**
 * Full-size status badge (运行中 / 已完成 / 失败). Where [StateDot] whispers, this speaks —
 * task cards lead with it so a list scans before it is read.
 */
@Composable
fun DsStatusPill(
    state: DsStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val (dot, tint) = when (state) {
        DsStatus.Running -> colors.accent to colors.accentTertiary
        DsStatus.Done -> colors.success to colors.successTertiary
        DsStatus.Failed -> colors.error to colors.errorTertiary
        DsStatus.Neutral -> colors.labelTertiary to colors.hover
    }
    Row(
        modifier = modifier
            .background(tint, DsShapes.pillFull)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(6.dp).background(dot, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = DsType.caption11Strong, color = dot)
    }
}

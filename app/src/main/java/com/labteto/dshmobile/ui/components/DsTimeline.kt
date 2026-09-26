package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** One entry on a [DsTimeline]: a run receipt, a step, an audit line. */
data class DsTimelineItem(
    val text: String,
    val state: DsStatus = DsStatus.Neutral,
)

/**
 * Vertical receipt timeline. Replaces the stacked caption-11 text walls on task cards: success,
 * failure and in-flight states read from the node color, not from re-reading the words.
 */
@Composable
fun DsTimeline(
    items: List<DsTimelineItem>,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Column(modifier) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(16.dp).height(24.dp)) {
                        // Connector above
                        if (index > 0) {
                            Box(
                                Modifier
                                    .offset(y = (-8).dp)
                                    .width(1.5.dp)
                                    .height(14.dp)
                                    .align(Alignment.TopCenter)
                                    .background(colors.borderL2),
                            )
                        }
                        // Connector below
                        if (index < items.lastIndex) {
                            Box(
                                Modifier
                                    .offset(y = 6.dp)
                                    .width(1.5.dp)
                                    .height(20.dp)
                                    .align(Alignment.TopCenter)
                                    .background(colors.borderL2),
                            )
                        }
                        val nodeColor = when (item.state) {
                            DsStatus.Running -> colors.accent
                            DsStatus.Done -> colors.success
                            DsStatus.Failed -> colors.error
                            DsStatus.Neutral -> colors.labelCaption
                        }
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .padding(vertical = 5.dp)
                                .size(8.dp)
                                .background(nodeColor, CircleShape),
                        )
                    }
                }
                Text(
                    item.text,
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                )
            }
        }
    }
}

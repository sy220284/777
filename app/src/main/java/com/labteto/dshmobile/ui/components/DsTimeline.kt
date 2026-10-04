package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** One entry on a [DsTimeline]: a run receipt, a step, an audit line. */
data class DsTimelineItem(
    val text: String,
    val state: DsStatus = DsStatus.Neutral,
    val detail: String? = null,
)

/**
 * Clear Realm vertical timeline.
 *
 * The connector provides order, the semantic dot provides state, and text stays container-less.
 * Only a live running node animates through [StateDot]; settled history is completely still.
 */
@Composable
fun DsTimeline(
    items: List<DsTimelineItem>,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Column(modifier) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(if (item.detail.isNullOrBlank()) 38.dp else 54.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (index > 0) {
                        Box(
                            Modifier
                                .offset(y = (-8).dp)
                                .width(1.5.dp)
                                .height(20.dp)
                                .background(colors.borderL2),
                        )
                    }
                    if (index < items.lastIndex) {
                        Box(
                            Modifier
                                .offset(y = 13.dp)
                                .width(1.5.dp)
                                .height(if (item.detail.isNullOrBlank()) 34.dp else 50.dp)
                                .background(colors.borderL2),
                        )
                    }
                    Box(Modifier.padding(top = 8.dp)) {
                        StateDot(
                            state = when (item.state) {
                                DsStatus.Running -> StateDotState.Running
                                DsStatus.Done -> StateDotState.Done
                                DsStatus.Warning -> StateDotState.Warning
                                DsStatus.Failed -> StateDotState.Error
                                DsStatus.Neutral -> StateDotState.Idle
                            },
                            size = 9.dp,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = DsSpacing.small, bottom = DsSpacing.xsmall),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        item.text,
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    item.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        Text(
                            detail,
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                    }
                }
            }
        }
    }
}

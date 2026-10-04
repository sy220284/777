package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.labteto.dshmobile.R
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
 * The rail follows the measured row height so long summaries and large text never break the line.
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
            val statusLabel = stringResource(when (item.state) {
                DsStatus.Running -> R.string.agent_operation_status_running
                DsStatus.Done -> R.string.agent_operation_status_done
                DsStatus.Failed -> R.string.audit_attempt_failed
                DsStatus.Warning -> R.string.audit_status_warning
                DsStatus.Neutral -> R.string.audit_status_idle
            })
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .semantics(mergeDescendants = true) { stateDescription = statusLabel },
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .fillMaxHeight(),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val centerX = size.width / 2f
                        val dotCenterY = 12.5.dp.toPx()
                        val stroke = 1.5.dp.toPx()
                        if (index > 0) {
                            drawLine(
                                color = colors.borderL2,
                                start = androidx.compose.ui.geometry.Offset(centerX, 0f),
                                end = androidx.compose.ui.geometry.Offset(centerX, dotCenterY),
                                strokeWidth = stroke,
                            )
                        }
                        if (index < items.lastIndex) {
                            drawLine(
                                color = colors.borderL2,
                                start = androidx.compose.ui.geometry.Offset(centerX, dotCenterY),
                                end = androidx.compose.ui.geometry.Offset(centerX, size.height),
                                strokeWidth = stroke,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp),
                    ) {
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
                        .padding(start = DsSpacing.small, bottom = DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        item.text,
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    listOfNotNull(item.detail?.takeIf(String::isNotBlank), statusLabel)
                        .joinToString(" · ").let { detail ->
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


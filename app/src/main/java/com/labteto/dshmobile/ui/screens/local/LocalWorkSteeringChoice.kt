package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.components.SyIconButton
import com.shenyan.designsystem.components.SyPill
import com.shenyan.designsystem.foundation.LocalSyColors

/** A running Work turn queues new messages by default. Show steering only while drafting. */
internal fun shouldShowWorkSteeringChoice(
    mode: LocalUsageMode,
    running: Boolean,
    teamDispatchSelected: Boolean,
    hasDraft: Boolean,
    dismissed: Boolean,
): Boolean = mode == LocalUsageMode.WORK && running && !teamDispatchSelected &&
    hasDraft && !dismissed

/**
 * Never put a long warning into the same row as the action: on 393dp phones it becomes
 * a one-character column that expands the entire composer to most of the viewport.
 */
@Composable
internal fun LocalWorkSteeringChoice(
    selected: Boolean,
    showSteering: Boolean,
    onToggle: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSyColors.current
    Column(Modifier.fillMaxWidth().testTag("work-steering-choice")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSteering) {
                SyPill(
                    text = stringResource(R.string.local_work_steer_now),
                    selected = selected,
                    onClick = onToggle,
                )
            }
            SyPill(
                text = stringResource(
                    if (selected && showSteering) R.string.local_work_submit_steer
                    else R.string.local_work_submit_queue,
                ),
                selected = true,
                onClick = onSubmit,
            )
            if (showSteering) {
                SyIconButton(
                    icon = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = onDismiss,
                    tint = colors.textSecondary,
                    iconSize = 18.dp,
                )
            }
        }
        if (selected && showSteering) {
            Text(
                text = stringResource(R.string.local_work_steer_boundary),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

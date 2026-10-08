package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Compact, user-readable work milestone. Raw tools and commands stay in Run Center. */
@Composable
internal fun WorkContentBlock(node: LocalWorkProcessNode, running: Boolean) {
    val summary = node.summary ?: if (node.failed) {
        stringResource(R.string.local_reasoning_work_failed)
    } else stringResource(agentOperationLabelRes(node.kind))
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        WorkOperationIcon(node.kind, running)
        Text(
            summary,
            modifier = Modifier.weight(1f),
            style = DsType.small13.withReadingWeight(),
            color = if (node.failed) colors.error else if (running) colors.labelPrimary else colors.labelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

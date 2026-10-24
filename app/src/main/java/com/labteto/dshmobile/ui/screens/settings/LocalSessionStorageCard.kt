package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric

import com.labteto.dshmobile.ui.components.FeatherIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyProgressBar
import com.labteto.dshmobile.ui.shenyan.SyProgressStatus
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun LocalSessionStorageCard(
    status: LocalSessionStorageStatus?,
    busy: Boolean,
    onCompact: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onLegacyRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onCleanup: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    SettingsCard(
        title = stringResource(R.string.settings_local_session_storage),
    ) {
        if (status == null) {
            Text(
                text = stringResource(R.string.common_loading),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                Text(formatStorageBytes(status.totalBytes),
                    style = DsType.display24.withReadingWeight().copy(fontFeatureSettings = "tnum"),
                    color = colors.text)
                Text(
                    text = stringResource(
                        R.string.settings_local_session_storage_usage,
                        formatStorageBytes(status.totalBytes),
                        formatStorageBytes(status.budgetBytes),
                    ),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = if (status.warning) colors.warningInk else colors.text,
                )
                // Design #25: truthful occupancy of the session archive only;
                // workspace/media/cache breakdowns require separate measured owners.
                SyProgressBar(
                    value = sessionStorageUsageFraction(status.totalBytes, status.budgetBytes),
                    color = if (status.warning) colors.warningInk else colors.primary,
                    status = SyProgressStatus.Running,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.settings_local_session_storage_free,
                        formatStorageBytes(status.freeBytes),
                        status.sessionCount,
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
                Text(
                    text = stringResource(
                        R.string.settings_local_session_storage_segments,
                        status.rawSegmentCount,
                        status.compressedSegmentCount,
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
                Text(
                    text = stringResource(
                        if (status.warning) {
                            R.string.settings_local_session_storage_warning
                        } else {
                            R.string.settings_local_session_storage_retention
                        },
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = if (status.warning) colors.warningInk else colors.textTertiary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            SyButton(
                text = stringResource(R.string.settings_local_session_storage_compact),
                onClick = onCompact,
                enabled = !busy && status != null,
                variant = SyButtonVariant.Outline,
                modifier = Modifier.weight(1f),
            )
            SyButton(
                text = stringResource(R.string.settings_local_session_storage_export),
                onClick = onExport,
                enabled = !busy && status != null,
                variant = SyButtonVariant.Outline,
                modifier = Modifier.weight(1f),
            )
        }
        SyButton(
            text = "导入会话备份（重启后生效）",
            onClick = onRestore,
            enabled = !busy && status?.pendingRestore != true,
            variant = SyButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
        SyButton(
            text = "转换旧版会话 ZIP（不包含原媒体文件）",
            onClick = onLegacyRestore,
            enabled = !busy && status?.pendingRestore != true,
            variant = SyButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
        if (status?.pendingRestore == true) {
            SyButton(
                text = "取消未开始的备份恢复",
                onClick = onCancelRestore,
                enabled = !busy,
                variant = SyButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SyButton(
            text = stringResource(R.string.settings_local_session_storage_cleanup),
            onClick = onCleanup,
            enabled = !busy,
            variant = SyButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val gib = 1024L * 1024L * 1024L
    val mib = 1024L * 1024L
    val kib = 1024L
    return when {
        safe >= gib -> String.format(java.util.Locale.US, "%.1f GiB", safe.toDouble() / gib)
        safe >= mib -> String.format(java.util.Locale.US, "%.1f MiB", safe.toDouble() / mib)
        safe >= kib -> String.format(java.util.Locale.US, "%.1f KiB", safe.toDouble() / kib)
        else -> "$safe B"
    }
}

/** Inclusive [0, 1] display fraction: zero/invalid budgets cannot produce NaN. */
internal fun sessionStorageUsageFraction(used: Long, budget: Long): Float =
    if (budget <= 0L) 0f else (used.coerceAtLeast(0L).toDouble() / budget.toDouble())
        .coerceIn(0.0, 1.0).toFloat()

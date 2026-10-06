package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun LocalSessionStorageCard(
    status: LocalSessionStorageStatus?,
    busy: Boolean,
    onCompact: () -> Unit,
    onExport: () -> Unit,
    onCleanup: () -> Unit,
) {
    val colors = DsTheme.colors
    SettingsCard(
        title = stringResource(R.string.settings_local_session_storage),
        icon = Icons.Outlined.History,
    ) {
        if (status == null) {
            Text(
                text = stringResource(R.string.common_loading),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_local_session_storage_usage,
                        formatStorageBytes(status.totalBytes),
                        formatStorageBytes(status.budgetBytes),
                    ),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = if (status.warning) colors.warnLabel else colors.labelPrimary,
                )
                Text(
                    text = stringResource(
                        R.string.settings_local_session_storage_free,
                        formatStorageBytes(status.freeBytes),
                        status.sessionCount,
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
                Text(
                    text = stringResource(
                        R.string.settings_local_session_storage_segments,
                        status.rawSegmentCount,
                        status.compressedSegmentCount,
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
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
                    color = if (status.warning) colors.warnLabel else colors.labelTertiary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsButton(
                text = stringResource(R.string.settings_local_session_storage_compact),
                onClick = onCompact,
                enabled = !busy && status != null,
                variant = DsButtonVariant.Outline,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.settings_local_session_storage_export),
                onClick = onExport,
                enabled = !busy && status != null,
                variant = DsButtonVariant.Outline,
                modifier = Modifier.weight(1f),
            )
        }
        DsButton(
            text = stringResource(R.string.settings_local_session_storage_cleanup),
            onClick = onCleanup,
            enabled = !busy,
            variant = DsButtonVariant.Ghost,
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

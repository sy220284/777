package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** The user explicitly enters the surface that owns executable capabilities. */
@Composable
internal fun LocalWorkCapabilitySheet(
    switching: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsBottomSheet(
        title = stringResource(R.string.work_capability_title),
        onDismiss = onDismiss,
        dismissEnabled = !switching,
        footer = {
            DsButton(
                text = stringResource(R.string.work_capability_continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                size = DsButtonSize.Large,
                enabled = enabled && !switching,
                loading = switching,
            )
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                enabled = !switching,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        },
    ) {
        Text(stringResource(R.string.work_capability_hint), style = DsType.std14.withReadingWeight(), color = colors.labelSecondary)
        if (failed) Text(stringResource(R.string.work_capability_failed), style = DsType.small13.withReadingWeight(), color = colors.error)
    }
}

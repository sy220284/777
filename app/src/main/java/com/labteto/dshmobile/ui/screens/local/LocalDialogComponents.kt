package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** A single-field rename dialog, shared by the session and workspace rename paths. */
@Composable
internal fun RenameDialog(
    initial: String,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    SyResponsiveSheet(title = title, onDismiss = onDismiss) {
        SyTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(title, style = DsType.std14.withReadingWeight()) },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SyButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = SyButtonVariant.Ghost,
            )
            Spacer(Modifier.width(SyMetric.gapMd))
            SyButton(
                text = stringResource(R.string.common_save),
                onClick = { onConfirm(text.trim()) },
                variant = SyButtonVariant.Info,
                enabled = text.trim().isNotEmpty(),
            )
        }
    }
}

/** A destructive confirmation with an explanatory body. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SyDialog(title = title, onDismiss = onDismiss) {
        Text(body, style = DsType.std14.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SyButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = SyButtonVariant.Ghost,
            )
            Spacer(Modifier.width(SyMetric.gapMd))
            SyButton(text = confirmLabel, onClick = onConfirm, variant = SyButtonVariant.Danger)
        }
    }
}

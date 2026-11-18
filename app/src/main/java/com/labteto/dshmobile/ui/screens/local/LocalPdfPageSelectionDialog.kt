package com.labteto.dshmobile.ui.screens.local

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.parseComposerPdfPages
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.shenyan.designsystem.components.SyTextField

@Composable
internal fun LocalPdfPageSelectionDialog(
    original: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(original.joinToString(",")) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    SyDialog(title = stringResource(R.string.local_pdf_choose_pages), onDismiss = onDismiss) {
        Text(stringResource(R.string.local_pdf_choose_pages_hint))
        SyTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            placeholder = { Text(stringResource(R.string.local_pdf_pages_placeholder)) },
            singleLine = true,
        )
        error?.let { Text(it) }
        SyButton(stringResource(R.string.local_pdf_apply_pages), onClick = {
            try {
                onConfirm(parseComposerPdfPages(draft))
            } catch (failure: IllegalArgumentException) {
                error = failure.message
            }
        })
    }
}

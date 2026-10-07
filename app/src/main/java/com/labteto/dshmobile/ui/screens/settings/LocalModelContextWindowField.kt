package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField

@Composable
internal fun LocalModelContextWindowField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    DsTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit).take(8)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.local_model_context_window)) },
        supportingText = { Text(stringResource(R.string.local_model_context_window_hint)) },
    )
}

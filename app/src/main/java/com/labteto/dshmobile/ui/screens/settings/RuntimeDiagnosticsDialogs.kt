package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

@Composable
internal fun NetworkDiagnosticDialog(
    onDismiss: () -> Unit,
    diagnose: suspend (String) -> String,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var target by rememberSaveable { mutableStateOf("https://github.com") }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.settings_runtime_network_diagnostic_failed)
    DsDialog(title = stringResource(R.string.settings_runtime_network_diagnostic_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.settings_runtime_network_diagnostic_intro),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_runtime_network_target_label)) },
            singleLine = true,
        )
        DsButton(
            text = stringResource(
                if (running) R.string.settings_runtime_network_diagnosing
                else R.string.settings_runtime_network_start_diagnostic,
            ),
            onClick = {
                running = true
                scope.launch {
                    result = runCatching { diagnose(target) }.getOrElse { it.message ?: failedText }
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = target.isNotBlank() && !running,
        )
        result?.let {
            SelectionContainer {
                Text(
                    it,
                    style = DsType.mdCode,
                    color = colors.labelPrimary,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
internal fun EnvironmentInfoDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = stringResource(R.string.settings_runtime_environment_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.settings_runtime_environment_intro),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        SelectionContainer {
            Text(
                text,
                style = DsType.mdCode,
                color = colors.labelPrimary,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

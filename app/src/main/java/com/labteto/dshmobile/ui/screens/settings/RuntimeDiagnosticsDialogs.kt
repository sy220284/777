package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.observability.isDiagnosticFailure
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NetworkDiagnosticDialog(
    onDismiss: () -> Unit,
    diagnose: suspend (String) -> String,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val scope = rememberCoroutineScope()
    var target by rememberSaveable { mutableStateOf("https://github.com") }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.settings_runtime_network_diagnostic_failed)
    SyResponsiveSheet(
        title = stringResource(R.string.settings_runtime_network_diagnostic_title),
        onDismiss = { if (!running) onDismiss() },
        scrollable = true,
        dismissEnabled = !running,
    ) {
        Text(
            stringResource(R.string.settings_runtime_network_diagnostic_intro),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        SyTextField(
            value = target,
            onValueChange = { target = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_runtime_network_target_label)) },
            singleLine = true,
        )
        SyButton(
            text = stringResource(
                if (running) R.string.settings_runtime_network_diagnosing
                else R.string.settings_runtime_network_start_diagnostic,
            ),
            onClick = {
                running = true
                scope.launch {
                    try {
                        result = diagnose(target)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.failure("Diagnostics", "network_probe", error)
                        result = error.message ?: failedText
                    } finally {
                        running = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = target.isNotBlank() && !running,
        )
        result?.let {
            SelectionContainer {
                Text(
                    it,
                    style = DsType.mdCode.withReadingWeight(),
                    color = colors.text,
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
    onExport: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var events by remember { mutableStateOf(AppLog.snapshot()) }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    val eventTime = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val emptyEvents = stringResource(R.string.settings_diagnostic_empty)
    SyResponsiveSheet(
        title = stringResource(R.string.settings_runtime_environment_title),
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        Text(
            stringResource(R.string.settings_runtime_environment_intro),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        SelectionContainer {
            Text(
                text,
                style = DsType.mdCode.withReadingWeight(),
                color = colors.text,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            )
        }
        // Design #29: count comes from actual AppLog records, not a demo counter.
        val failureCount = events.count { it.isDiagnosticFailure() }
        SySegmented(
            segments = listOf(
                SySegment(
                    "all",
                    "${stringResource(R.string.settings_diagnostic_show_all)} · ${events.size}",
                ),
                SySegment(
                    "errors",
                    "${stringResource(R.string.settings_diagnostic_errors_only)} · ${failureCount}",
                ),
            ),
            selectedKey = if (errorsOnly) "errors" else "all",
            onSelect = { errorsOnly = it == "errors" },
            role = Role.Tab,
            stretch = true,
            hapticOnChange = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SelectionContainer {
            val visible = events.filter { !errorsOnly || it.isDiagnosticFailure() }.takeLast(40)
            Text(
                if (visible.isEmpty()) emptyEvents else visible.joinToString("\n") {
                    "${eventTime.format(Date(it.timestampMillis))} ${it.level}/${it.tag} ${it.throwableType.orEmpty()} ${it.message.replace("\n", " ").take(240)}"
                },
                style = DsType.mdCode.withReadingWeight(),
                color = colors.text,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            )
        }
        SyButton(text = stringResource(R.string.settings_diagnostic_refresh), onClick = { events = AppLog.snapshot() }, modifier = Modifier.fillMaxWidth())
        SyButton(text = stringResource(R.string.settings_export_diagnostics), onClick = onExport, modifier = Modifier.fillMaxWidth())
    }
}

package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.*
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bounded page in memory; changing session or dismissing cancels the read. */
@Composable
internal fun LocalWorkHistorySheet(
    sessionId: String,
    readPage: (String, LocalWorkHistoryCursor?) -> LocalWorkHistoryPageUi,
    readEvidence: (String, String, Long) -> String?,
    onOpenResults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var artifactFailed by remember(sessionId) { mutableStateOf(false) }
    var selected by remember(sessionId) { mutableStateOf<LocalWorkHistoryRecord?>(null) }
    var evidence by remember(sessionId, selected?.sequence) { mutableStateOf<String?>(null) }
    var evidenceFailed by remember(sessionId, selected?.sequence) { mutableStateOf(false) }
    LaunchedEffect(sessionId, selected?.sequence) {
        val record = selected ?: return@LaunchedEffect
        try {
            evidence = withContext(Dispatchers.IO) { record.callId?.let { readEvidence(sessionId, it, record.sequence) } }
            evidenceFailed = evidence == null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { evidenceFailed = true }
    }
    var cursor by remember(sessionId) { mutableStateOf<LocalWorkHistoryCursor?>(null) }
    var page by remember(sessionId) { mutableStateOf<LocalWorkHistoryPageUi?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var retry by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(sessionId, cursor, retry) {
        page = null
        failed = false
        try {
            page = withContext(Dispatchers.IO) { readPage(sessionId, cursor) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    DsBottomSheet(title = stringResource(R.string.local_run_full_history), onDismiss = onDismiss,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(stringResource(R.string.local_run_history_latest), { selected = null; cursor = null; retry++ },
                    variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
                if (failed) DsButton(stringResource(R.string.common_retry), { retry++ }, size = DsButtonSize.Small)
                else DsButton(stringResource(R.string.local_run_history_older), { selected = null; cursor = page?.older },
                    enabled = page?.older != null, size = DsButtonSize.Small)
            }
        }) {
        val current = page
        val detail = selected
        if (artifactFailed) Text(stringResource(R.string.local_artifact_open_failed), color = DsTheme.colors.error)
        if (detail != null) {
            DsButton(stringResource(R.string.common_back), { selected = null }, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            if (evidenceFailed) Text(stringResource(R.string.local_tool_activity_evidence_error))
            SelectionContainer { Text(evidence ?: detail.content, Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), style = DsType.small13) }
        } else when {
            failed -> Text(stringResource(R.string.local_run_history_failed), color = DsTheme.colors.labelSecondary)
            current == null -> Text(stringResource(R.string.common_loading))
            current.invalidated -> Text(stringResource(R.string.local_run_history_changed))
            current.records.isEmpty() -> Text(stringResource(R.string.local_run_history_page_empty))
            else -> key(cursor, retry) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                    items(current.records, key = { it.sequence }) { record ->
                        Column {
                            Text(stringResource(R.string.local_run_history_record, record.sequence, record.name,
                                when (record.type) {
                                    "tool/call" -> stringResource(R.string.local_run_history_call)
                                    "tool/execution-started" -> stringResource(R.string.local_run_history_started)
                                    else -> stringResource(R.string.local_run_history_result)
                                }), style = DsType.small13.withReadingWeight(), color = DsTheme.colors.labelSecondary)
                            record.artifacts.forEach { artifact ->
                                DsSheetChoiceRow(title = artifact.reference,
                                    subtitle = if (artifact.currentlyAvailable == false) stringResource(R.string.local_artifact_file_unavailable) else null,
                                    onClick = {
                                        artifactFailed = false
                                        when (artifact.category) {
                                            "file" -> if (artifact.currentlyAvailable == false) artifactFailed = true else { onDismiss(); onOpenResults() }
                                            "link" -> runCatching { uriHandler.openUri(artifact.reference) }.onFailure { artifactFailed = true }
                                            else -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(artifact.reference))
                                        }
                                    })
                            }
                            SelectionContainer { Text(record.content, style = DsType.small13.withReadingWeight()) }
                            if (record.callId != null) DsButton(stringResource(R.string.local_tool_activity_details),
                                { selected = record }, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
                        }
                    }
                }
            }
        }
    }
}

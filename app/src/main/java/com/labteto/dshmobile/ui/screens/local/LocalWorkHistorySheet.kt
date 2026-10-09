package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.*
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
    onDismiss: () -> Unit,
) {
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
                DsButton(stringResource(R.string.local_run_history_latest), { cursor = null; retry++ },
                    variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
                if (failed) DsButton(stringResource(R.string.common_retry), { retry++ }, size = DsButtonSize.Small)
                else DsButton(stringResource(R.string.local_run_history_older), { cursor = page?.older },
                    enabled = page?.older != null, size = DsButtonSize.Small)
            }
        }) {
        val current = page
        when {
            failed -> Text(stringResource(R.string.local_run_history_failed), color = DsTheme.colors.labelSecondary)
            current == null -> Text(stringResource(R.string.common_loading))
            current.invalidated -> Text(stringResource(R.string.local_run_history_changed))
            current.records.isEmpty() -> Text(stringResource(R.string.local_run_history_page_empty))
            else -> key(cursor, retry) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                    items(current.records, key = { it.sequence }) { record ->
                        Column {
                            Text(stringResource(R.string.local_run_history_record, record.sequence, record.name,
                                when (record.type) {
                                    "tool/call" -> stringResource(R.string.local_run_history_call)
                                    "tool/execution-started" -> stringResource(R.string.local_run_history_started)
                                    else -> stringResource(R.string.local_run_history_result)
                                }), style = DsType.small13.withReadingWeight(), color = DsTheme.colors.labelSecondary)
                            SelectionContainer { Text(record.content, style = DsType.small13.withReadingWeight()) }
                        }
                    }
                }
            }
        }
    }
}

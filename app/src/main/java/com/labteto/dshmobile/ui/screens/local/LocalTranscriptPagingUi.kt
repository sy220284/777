package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Owns the foreground transcript paging interaction.
 *
 * Runtime/event history remains independently bounded. This controller only observes the reader's
 * position and asks the history owner for an older user-visible batch when the reader approaches
 * the top. A failed request deliberately stops automatic retries until the user asks again.
 */
@Composable
internal fun LocalTranscriptAutoPager(
    sessionId: String,
    listState: LazyListState,
    enabled: Boolean,
    onLoadOlder: suspend (String) -> Result<Int>,
) {
    LaunchedEffect(sessionId, listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex <= LOCAL_TRANSCRIPT_AUTOLOAD_THRESHOLD_ITEMS
        }
            .distinctUntilChanged()
            .collect { nearStart ->
                if (nearStart) onLoadOlder(sessionId)
            }
    }
}

/** Small status surface for the exceptional history-loading states only. */
@Composable
internal fun LocalTranscriptPagingStatus(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    if (error != null) {
        DsButton(
            text = stringResource(R.string.local_transcript_retry_older),
            onClick = onRetry,
            modifier = modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
        return
    }
    if (!loading) return

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(DsSpacing.small))
        Text(
            stringResource(R.string.local_transcript_loading_older),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
    }
}

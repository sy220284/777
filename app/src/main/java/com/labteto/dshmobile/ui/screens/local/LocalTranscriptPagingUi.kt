package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric


import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
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
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
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

/**
 * Keeps a transcript tail that was already visible pinned to the composer while focus changes the
 * available viewport.
 *
 * IME insets and the expanded composer both resize the LazyColumn frame by frame. Following the
 * measured viewport delta keeps the last message moving with that resize instead of letting the
 * keyboard/composer cover it. The caller only enables this after confirming the tail is visible,
 * so focusing the composer while reading older history does not steal the reader's position.
 */
@Composable
internal fun LocalComposerTailFollower(
    listState: LazyListState,
    anchoredViewportExtent: Int?,
) {
    LaunchedEffect(listState, anchoredViewportExtent) {
        var previousViewportExtent = anchoredViewportExtent ?: return@LaunchedEffect
        snapshotFlow { listState.conversationViewportExtent() }
            .collect { currentViewportExtent ->
                val viewportDelta = previousViewportExtent - currentViewportExtent
                previousViewportExtent = currentViewportExtent
                if (viewportDelta != 0) {
                    listState.scrollBy(viewportDelta.toFloat())
                }
            }
    }
}

/**
 * Follows the *growth* of a Chat or Work streaming message while the reader remains
 * near the conversation bottom. Observing item size instead of scroll offset prevents
 * ordinary manual scrolling from being mistaken for new model output.
 */
@Composable
internal fun LocalWorkStreamingTailFollower(
    listState: LazyListState,
    enabled: Boolean,
) {
    // This detects actual drag gestures, so programmatic tail-follow scrolling does
    // not accidentally disable itself. A reader who scrolls upward keeps their place.
    val readerDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState, enabled, readerDragging) {
        if (!enabled || readerDragging) return@LaunchedEffect
        var following = false
        snapshotFlow {
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.lastOrNull()
            val isTail = tail?.index == info.totalItemsCount - 1
            val gap = if (isTail && tail != null) {
                tail.offset + tail.size - info.viewportEndOffset
            } else null
            gap
        }.distinctUntilChanged().collect { gap ->
            if (gap == null) {
                following = false
                return@collect
            }
            // Establish the initial anchor only near the bottom. Once anchored,
            // follow even a whole Markdown paragraph arriving in one large burst.
            if (gap in -128..64) following = true
            if (following && gap > 0) listState.scrollBy(gap.toFloat())
        }
    }
}

/**
 * The newest Work event glides into place only when the user was following the tail.
 * A manual drag pauses following; returning to the bottom opts back in automatically.
 * The existing streaming-size follower runs while this item transition is idle.
 */
@Composable
internal fun LocalWorkTimelineAutoScroller(
    listState: LazyListState,
    sessionId: String,
    tailIndex: Int,
    tailKey: String,
    isReady: Boolean,
    isCatchingUp: MutableState<Boolean>,
    isReaderPaused: MutableState<Boolean>,
) {
    val readerDragging by listState.interactionSource.collectIsDraggedAsState()
    var previousTailIndex by remember(sessionId, listState) { mutableStateOf(-1) }
    var previousTailKey by remember(sessionId, listState) { mutableStateOf("") }

    LaunchedEffect(readerDragging, sessionId, listState) {
        if (readerDragging) {
            isReaderPaused.value = true
        } else if (listState.isAtWorkTimelineBottom()) {
            isReaderPaused.value = false
        }
    }
    // The collector must restart with the actual drag state; otherwise a captured
    // stale false could resume following while the reader is still touching the list.
    LaunchedEffect(sessionId, listState, readerDragging) {
        snapshotFlow { listState.isAtWorkTimelineBottom() }
            .distinctUntilChanged()
            .collect { atBottom ->
                if (atBottom && !readerDragging) isReaderPaused.value = false
            }
    }
    LaunchedEffect(sessionId, listState, tailIndex, tailKey, isReady, readerDragging) {
        val oldTail = previousTailIndex
        val oldKey = previousTailKey
        previousTailIndex = tailIndex
        previousTailKey = tailKey
        if (!isReady || oldTail < 0 || tailIndex < 0 ||
            (tailIndex <= oldTail && tailKey == oldKey)
        ) return@LaunchedEffect
        // Following is reader intent, not a measurement of the old tail AFTER new rows arrived.
        // Batched background output and hot-window eviction can remove that old row entirely.
        if (isReaderPaused.value || readerDragging) return@LaunchedEffect
        isCatchingUp.value = true
        try {
            listState.animateScrollToItem(tailIndex)
            if (!isReaderPaused.value) {
                listState.alignWorkTimelineTailAfterScroll(tailIndex)
            }
        } finally {
            isCatchingUp.value = false
        }
    }
}

/**
 * animateScrollToItem brings a tall final answer to its START, not its bottom.
 * Finish near the actual reading tail: gently move short distances, but skip a
 * giant paragraph in one step instead of animating through thousands of pixels.
 */
internal suspend fun LazyListState.alignWorkTimelineTailAfterScroll(tailIndex: Int) {
    val info = layoutInfo
    val tail = info.visibleItemsInfo.lastOrNull { it.index == tailIndex } ?: return
    val gap = tail.offset + tail.size - info.viewportEndOffset
    if (gap <= 0) return
    val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    if (gap > viewportHeight) scrollBy(gap.toFloat())
    else animateScrollBy(gap.toFloat())
}

private fun LazyListState.isAtWorkTimelineBottom(): Boolean {
    val info = layoutInfo
    val tail = info.visibleItemsInfo.lastOrNull() ?: return false
    if (tail.index != info.totalItemsCount - 1) return false
    return tail.offset + tail.size - info.viewportEndOffset <= 72
}

/** Counts every item actually emitted before the transcript tail in LazyColumn. */
internal fun localTranscriptLastListIndex(
    transcriptItemCount: Int,
    showPagingRow: Boolean,
    showEmptyWorkbench: Boolean,
    showEmptyGroupSetup: Boolean,
    showPlanReview: Boolean,
    showStreamingTail: Boolean,
): Int = transcriptItemCount +
    (if (showPagingRow) 1 else 0) +
    (if (showEmptyWorkbench) 1 else 0) +
    (if (showEmptyGroupSetup) 1 else 0) +
    (if (showPlanReview) 1 else 0) +
    (if (showStreamingTail) 1 else 0) - 1

internal fun LazyListState.conversationViewportExtent(): Int {
    val info = layoutInfo
    return info.viewportEndOffset - info.viewportStartOffset
}

internal fun LazyListState.isConversationTailVisible(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount <= 0) return false
    return info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
}

/** Small status surface for the exceptional history-loading states only. */
@Composable
internal fun LocalTranscriptPagingStatus(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    if (error != null) {
        SyButton(
            text = stringResource(R.string.local_transcript_retry_older),
            onClick = onRetry,
            modifier = modifier.fillMaxWidth(),
            variant = SyButtonVariant.Ghost,
        )
        return
    }
    if (!loading) return

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = SyMetric.gapMd),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(SyMetric.gapMd))
        Text(
            stringResource(R.string.local_transcript_loading_older),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
    }
}

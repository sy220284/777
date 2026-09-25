package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import kotlin.math.abs
import kotlinx.coroutines.delay

enum class ConversationScrollTarget { START, LATEST }

class ConversationScrollHint internal constructor() {
    var target by mutableStateOf<ConversationScrollTarget?>(null)
        private set
    private var accumulatedDrag = 0f

    internal fun onFingerScroll(deltaY: Float, canReachStart: Boolean, canReachLatest: Boolean) {
        if (deltaY * accumulatedDrag < 0f) accumulatedDrag = 0f
        accumulatedDrag += deltaY
        if (abs(accumulatedDrag) < 18f) return
        target = if (accumulatedDrag > 0f) {
            ConversationScrollTarget.START.takeIf { canReachStart }
        } else {
            ConversationScrollTarget.LATEST.takeIf { canReachLatest }
        }
        accumulatedDrag = 0f
    }

    fun hide() {
        accumulatedDrag = 0f
        target = null
    }
}

/** Only a real finger scroll can reveal the shortcut; programmatic scrolling cannot. */
@Composable
fun rememberConversationScrollHint(
    listState: LazyListState,
    reverseLayout: Boolean,
): Pair<ConversationScrollHint, NestedScrollConnection> {
    val hint = remember(listState) { ConversationScrollHint() }
    val connection = remember(listState, reverseLayout) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    hint.onFingerScroll(
                        deltaY = available.y,
                        canReachStart = if (reverseLayout) listState.canScrollForward else listState.canScrollBackward,
                        canReachLatest = if (reverseLayout) listState.canScrollBackward else listState.canScrollForward,
                    )
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress, hint.target) {
        if (!listState.isScrollInProgress && hint.target != null) {
            delay(2_400)
            hint.hide()
        }
    }
    return hint to connection
}

/** Compact arrow-only affordance that adapts to the wallpaper under the composer area. */
@Composable
fun ConversationScrollShortcut(
    target: ConversationScrollTarget?,
    onClick: (ConversationScrollTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val surfaceColor = backgroundState.surfaceColor(
        base = colors.bgLayer1,
        region = BackgroundRegion.BOTTOM,
        minAlpha = 0.80f,
        maxAlpha = 0.97f,
    )
    val borderColor = if (backgroundState.hasImage && backgroundState.adaptiveContrast) {
        colors.borderL1.copy(alpha = 0.72f)
    } else {
        colors.borderL1
    }

    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith
                (fadeOut() + scaleOut(targetScale = 0.85f))
        },
        label = "conversationScrollShortcut",
    ) { direction ->
        if (direction != null) {
            val label = stringResource(
                if (direction == ConversationScrollTarget.START) R.string.chat_scroll_start
                else R.string.chat_scroll_latest,
            )
            Surface(
                onClick = { onClick(direction) },
                shape = CircleShape,
                color = surfaceColor,
                border = BorderStroke(1.dp, borderColor),
                shadowElevation = 2.dp,
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (direction == ConversationScrollTarget.START) Icons.Filled.ArrowUpward
                        else Icons.Filled.ArrowDownward,
                        contentDescription = label,
                        tint = colors.labelPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

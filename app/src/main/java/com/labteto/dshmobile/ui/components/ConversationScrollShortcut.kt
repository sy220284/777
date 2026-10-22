package com.labteto.dshmobile.ui.components

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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

/** Transparent arrow-only scroll shortcut, without a filled surface or elevation. */
@Composable
fun ConversationScrollShortcut(
    target: ConversationScrollTarget?,
    onClick: (ConversationScrollTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val borderColor = colors.border.copy(alpha = 0.28f)

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
            Box(
                modifier = Modifier
                    .size(SyMetric.touch)
                    .clickable { onClick(direction) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(BorderStroke(1.dp, borderColor), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (direction == ConversationScrollTarget.START) FeatherIcons.ArrowUp
                        else FeatherIcons.ArrowDown,
                        contentDescription = label,
                        tint = colors.text,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

package com.labteto.dshmobile.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import kotlinx.coroutines.launch

/**
 * Discord-style shell:
 *  - swipe right in the content area to open the chat-list drawer
 *  - inward swipes from either system edge remain Android Back gestures
 *  - swipe left while the drawer is open closes it
 *  - the explicit top-bar affordance opens the session Details panel
 *  - swipe right inside the open Details panel closes it
 *
 * Opening Details deliberately avoids a right-edge gesture: Android reserves both screen edges
 * for system back navigation, so claiming either edge makes a core app action compete with the OS.
 * The detector below exists only while Details is open and only claims rightward drags that start
 * inside the visible Details panel.
 */
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenLocalHarness: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenTools: () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var detailsOpen by remember { mutableStateOf(false) }
    val detailsWidth = 300.dp

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            detailsOpen -> detailsOpen = false
            else -> onOpenLocalHarness()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !detailsOpen,
        drawerContent = {
            ChatListDrawer(
                onClose = { scope.launch { drawerState.close() } },
                onOpenSettings = onOpenSettings,
                onOpenLocalHarness = onOpenLocalHarness,
                onOpenTasks = onOpenTasks,
                onOpenTools = onOpenTools,
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expandedLayout = maxWidth >= 840.dp
            if (expandedLayout) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        ChatScreen(
                            onOpenDetails = { detailsOpen = true },
                            detailsOpen = detailsOpen,
                        )
                    }
                    AnimatedVisibility(
                        visible = detailsOpen,
                        enter = slideInHorizontally(DsAnimations.panelSlide) { it / 3 },
                        exit = slideOutHorizontally(DsAnimations.panelSlide) { it / 3 },
                    ) {
                        DetailsPanel(
                            onClose = { detailsOpen = false },
                            modifier = Modifier.width(340.dp).fillMaxHeight(),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(detailsOpen) {
                            if (!detailsOpen) return@pointerInput
                            val width = size.width.toFloat()
                            val detailsWidthPx = detailsWidth.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!detailsPanelOwnsRightSwipe(down.position.x, width, detailsWidthPx)) {
                                    return@awaitEachGesture
                                }

                                var claimed = false
                                awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                    claimed = overSlop > 0f
                                    if (claimed) change.consume()
                                } ?: return@awaitEachGesture
                                if (!claimed) return@awaitEachGesture

                                var totalX = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) break
                                    if (change.isConsumed) break
                                    totalX += change.positionChange().x
                                    change.consume()
                                    if (totalX >= width * 0.12f) {
                                        detailsOpen = false
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    ChatScreen(
                        onOpenDetails = { detailsOpen = true },
                        detailsOpen = detailsOpen,
                    )
                    AnimatedVisibility(
                        visible = detailsOpen,
                        enter = slideInHorizontally(DsAnimations.panelSlide) { it },
                        exit = slideOutHorizontally(DsAnimations.panelSlide) { it },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        DetailsPanel(
                            onClose = { detailsOpen = false },
                            modifier = Modifier.width(detailsWidth),
                        )
                    }
                }
            }
        }
    }
}

internal fun detailsPanelOwnsRightSwipe(
    startX: Float,
    containerWidth: Float,
    panelWidth: Float,
): Boolean {
    if (!startX.isFinite() || !containerWidth.isFinite() || !panelWidth.isFinite()) return false
    if (containerWidth <= 0f || panelWidth <= 0f) return false

    val visiblePanelWidth = panelWidth.coerceAtMost(containerWidth)
    val panelStartX = containerWidth - visiblePanelWidth
    return startX in panelStartX..containerWidth
}

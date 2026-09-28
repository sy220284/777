package com.labteto.dshmobile.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 *  - swipe right from the LEFT edge (or anywhere on the content) opens the chat-list drawer
 *    (ModalNavigationDrawer's built-in gesture; swipe left on the drawer
 *    content closes it, scrim tap and Back also work)
 *  - the explicit top-bar affordance opens the session Details panel
 *  - swipe right on the open Details panel closes it
 *
 * Opening Details deliberately avoids a right-edge gesture: Android reserves both screen edges
 * for system back navigation, so claiming that band makes a core app action compete with the OS.
 * The detector below exists only while Details is open and only claims rightward drags that start
 * inside the panel.
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(detailsOpen) {
                    if (!detailsOpen) return@pointerInput
                    val width = size.width.toFloat()
                    val detailsAreaPx = detailsWidth.toPx() * 0.9f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x > detailsAreaPx) return@awaitEachGesture

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
                onOpenDrawer = { scope.launch { drawerState.open() } },
                detailsOpen = detailsOpen,
            )

            AnimatedVisibility(
                visible = detailsOpen,
                // Explicit spec: the platform default runs 300ms, which lags behind the drag the
                // panel is usually opened with.
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

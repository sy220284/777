package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.ConnectionBanner
import com.labteto.dshmobile.ui.components.ConversationScrollShortcut
import com.labteto.dshmobile.ui.components.ConversationScrollTarget
import com.labteto.dshmobile.ui.components.rememberConversationScrollHint
import com.labteto.dshmobile.ui.theme.DsAnimations
import kotlinx.coroutines.launch

/**
 * Owns the high-frequency conversation subscription.
 *
 * Streaming deltas recompose this leaf instead of the whole ChatScreen, so attachment pickers,
 * sheets, model selectors and other low-frequency chrome stay outside the token-rate invalidation
 * boundary.
 */
@Composable
internal fun ChatConversationSurface(
    store: SessionStore,
    currentSessionId: String?,
    currentSession: SessionRow?,
    composerKey: ComposerKey,
    tab: ChatTab,
    approvalSessionId: String?,
    questionSessionId: String?,
    onOpenSubagent: (String) -> Unit,
    onFeedback: (String, Boolean) -> Unit,
    onCopied: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val loadingOlder by store.loadingOlder.collectAsStateWithLifecycle()
    val loadOlderFailed by store.loadOlderFailed.collectAsStateWithLifecycle()
    val chatListState = rememberLazyListState()
    val (scrollHint, scrollConnection) =
        rememberConversationScrollHint(chatListState, reverseLayout = true)
    val trajectoryListState = rememberLazyListState()

    if (conversation?.gap == true) {
        ConnectionBanner(androidx.compose.ui.res.stringResource(com.labteto.dshmobile.R.string.common_reconnecting))
    }

    val contextNodes = conversation?.nodes.orEmpty()
    val eventTimes = remember(conversation?.journal) {
        conversation?.journal?.associate { it.seq to it.time }.orEmpty()
    }
    val nodeContext = remember(
        contextNodes,
        eventTimes,
        conversation?.running,
        currentSession?.cwd,
        currentSessionId,
        composerKey,
    ) {
        ChatNodeContext(
            nodes = contextNodes,
            eventTimes = eventTimes,
            running = conversation?.running == true,
            cwd = currentSession?.cwd,
            onOpenSubagent = onOpenSubagent,
            onBranchFrom = { seq ->
                scope.launch { currentSessionId?.let { store.forkSession(it, seq) } }
            },
            onFeedback = { seq, positive ->
                contextNodes
                    .filterIsInstance<AssistantMessageNode>()
                    .firstOrNull { it.seq == seq }
                    ?.messageId
                    ?.let { onFeedback(it, positive) }
            },
            onCopied = onCopied,
        )
    }

    Box(
        Modifier.fillMaxWidth().then(
            if (tab == ChatTab.Chat) Modifier.nestedScroll(scrollConnection) else Modifier,
        ),
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (
                    slideInHorizontally { width -> if (forward) width / 6 else -width / 6 } +
                        fadeIn(DsAnimations.fade)
                    )
                    .togetherWith(fadeOut(DsAnimations.fade)) using SizeTransform(clip = false)
            },
            modifier = Modifier.fillMaxWidth(),
            label = "chatTab",
        ) { current ->
            when (current) {
                ChatTab.Chat -> ChatTranscript(
                    conversation = conversation,
                    loading = conversation == null && currentSessionId != null,
                    loadingOlder = loadingOlder,
                    loadOlderFailed = loadOlderFailed,
                    context = nodeContext,
                    listState = chatListState,
                    onLoadOlder = { scope.launch { store.loadOlder() } },
                )
                ChatTab.Trajectory -> {
                    val stats by store.sessionStats.collectAsStateWithLifecycle()
                    val usage by store.tokenUsage.collectAsStateWithLifecycle()
                    TrajectoryTab(
                        conversation = conversation,
                        stats = stats,
                        usage = usage,
                        cwd = currentSession?.cwd,
                        listState = trajectoryListState,
                    )
                }
            }
        }
        if (tab == ChatTab.Chat) {
            ConversationScrollShortcut(
                target = scrollHint.target,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
                onClick = { target ->
                    scrollHint.hide()
                    scope.launch {
                        chatListState.animateScrollToItem(
                            if (target == ConversationScrollTarget.START) {
                                (chatListState.layoutInfo.totalItemsCount -
                                    if (conversation?.hasMore == true) 2 else 1).coerceAtLeast(0)
                            } else {
                                0
                            },
                        )
                    }
                },
            )
        }
    }

    val hasBlockingInteraction =
        approvalSessionId == currentSessionId || questionSessionId == currentSessionId
    if (!hasBlockingInteraction) {
        conversation?.let { conv ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                parseTodos(conv.projections["todos"])?.let { TodoDock(it) }
                parseGoal(conv.projections["goal"])?.let { GoalBar(it, store) }
                QueueDock(conv.queue, store)
            }
        }
    }
}

@Composable
internal fun ConversationWorkspacePanelHost(
    store: SessionStore,
    key: ComposerKey,
    currentComposerKey: ComposerKey,
    currentCwd: String?,
    mode: WorkspacePanelMode,
    onDismiss: () -> Unit,
) {
    val conversationFiles = if (key == currentComposerKey) {
        val conversation by store.currentConversation.collectAsStateWithLifecycle()
        remember(conversation?.nodes, currentCwd) {
            conversationFileIndex(conversation?.nodes.orEmpty(), currentCwd)
        }
    } else {
        ConversationFileIndex()
    }
    WorkspacePanels(
        store = store,
        state = store.panels.get(key),
        mode = mode,
        conversationFiles = conversationFiles,
        onDismiss = onDismiss,
    )
}

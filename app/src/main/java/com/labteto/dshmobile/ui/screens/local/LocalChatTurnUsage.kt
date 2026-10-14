package com.labteto.dshmobile.ui.screens.local


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.screens.settings.UsageGroupDetailPage
import com.labteto.dshmobile.ui.screens.settings.UsageRequestDetailPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal class LocalChatTurnUsageUi {
    var summaries by mutableStateOf<Map<String, TokenUsageAggregate>>(emptyMap())
    var selectedTurn by mutableStateOf<String?>(null)
    fun open(turnId: String) { selectedTurn = turnId }
}

@Composable
internal fun LocalChatTurnUsageButton(aggregate: TokenUsageAggregate, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp).testTag("chat-turn-usage"),
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
        ) {
            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (aggregate.unreportedRequestCount > 0) R.string.local_chat_turn_usage_incomplete
                        else R.string.local_chat_turn_usage_known, aggregate.totalTokens),
                    style = DsType.caption11.withReadingWeight(),
                    color = LocalSyColors.current.textTertiary,
                )
            }
        }
    }
}

/** One ledger read per revision for the screen, rather than one read for each visible message. */
@Composable
internal fun LocalChatTurnUsageController(
    sessionId: String,
    enabled: Boolean,
    revision: StateFlow<Long>?,
    loadSummaries: (String) -> Map<String, TokenUsageAggregate>,
    loadTurn: (String, String) -> TokenUsageGroupDetail?,
    loadRequest: (String) -> TokenUsageRecord?,
): LocalChatTurnUsageUi {
    val idleRevision = remember { MutableStateFlow(0L) }
    val version by (revision ?: idleRevision).collectAsStateWithLifecycle()
    val ui = remember(sessionId, enabled) { LocalChatTurnUsageUi() }
    var requestId by remember(sessionId, ui.selectedTurn) { mutableStateOf<String?>(null) }
    var detail by remember(sessionId, ui.selectedTurn) { mutableStateOf<TokenUsageGroupDetail?>(null) }
    var request by remember(sessionId, requestId) { mutableStateOf<TokenUsageRecord?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var loading by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, enabled, version) {
        if (!enabled) return@LaunchedEffect
        try {
            ui.summaries = withContext(Dispatchers.IO) { loadSummaries(sessionId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ui.summaries = emptyMap()
        }
    }
    LaunchedEffect(sessionId, ui.selectedTurn, requestId, version) {
        val turn = ui.selectedTurn ?: return@LaunchedEffect
        loading = true
        failed = false
        try {
            val id = requestId
            if (id == null) detail = withContext(Dispatchers.IO) { loadTurn(sessionId, turn) }
            else request = withContext(Dispatchers.IO) { loadRequest(id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    if (enabled && ui.selectedTurn != null) SyResponsiveSheet(
        title = stringResource(R.string.local_chat_turn_usage_title),
        onDismiss = { ui.selectedTurn = null }, scrollable = true,
    ) {
        Text(stringResource(R.string.local_chat_turn_usage_scope))
        if (requestId != null) SyButton(stringResource(R.string.common_back), onClick = { requestId = null }, variant = SyButtonVariant.Ghost)
        when {
            loading -> Text(stringResource(R.string.local_run_usage_loading))
            failed -> Text(stringResource(R.string.local_run_usage_unavailable))
            requestId != null -> UsageRequestDetailPage(request)
            else -> {
                detail?.aggregate?.let { aggregate ->
                    Text(stringResource(R.string.local_run_usage_known, aggregate.totalTokens))
                    if (aggregate.unreportedRequestCount > 0) Text(stringResource(R.string.local_run_usage_missing, aggregate.unreportedRequestCount))
                }
                UsageGroupDetailPage(detail, onOpenRequest = { requestId = it })
            }
        }
    }
    return ui
}

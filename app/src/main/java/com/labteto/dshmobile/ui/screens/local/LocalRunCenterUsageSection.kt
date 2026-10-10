package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.screens.settings.UsageGroupDetailPage
import com.labteto.dshmobile.ui.screens.settings.UsageRequestDetailPage
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@Composable
internal fun LocalRunCenterUsageSection(
    sessionId: String,
    revision: StateFlow<Long>,
    loadSession: (String) -> TokenUsageAnalyticsSnapshot,
    loadTask: (String) -> TokenUsageGroupDetail?,
    loadRequest: (String) -> TokenUsageRecord?,
) {
    val version by revision.collectAsStateWithLifecycle()
    var snapshot by remember(sessionId) { mutableStateOf<TokenUsageAnalyticsSnapshot?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var detailFailed by remember(sessionId) { mutableStateOf(false) }
    var visibleTasks by remember(sessionId) { mutableStateOf(10) }
    var open by remember(sessionId) { mutableStateOf(false) }
    var taskId by remember(sessionId) { mutableStateOf<String?>(null) }
    var requestId by remember(sessionId) { mutableStateOf<String?>(null) }
    var detail by remember(sessionId, taskId) { mutableStateOf<TokenUsageGroupDetail?>(null) }
    var request by remember(sessionId, requestId) { mutableStateOf<TokenUsageRecord?>(null) }
    var loading by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, version, open) {
        try {
            snapshot = withContext(Dispatchers.IO) { loadSession(sessionId) }
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(open, taskId, requestId, version) {
        if (!open || taskId == null) {
            loading = false
            detailFailed = false
            return@LaunchedEffect
        }
        loading = true
        detailFailed = false
        try {
            if (requestId != null) request = withContext(Dispatchers.IO) { loadRequest(checkNotNull(requestId)) }
            else detail = withContext(Dispatchers.IO) { loadTask(checkNotNull(taskId)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            detailFailed = true
        } finally {
            loading = false
        }
    }
    Column(Modifier.padding(horizontal = DsSpacing.medium)) {
        DsButton(
            text = if (failed) stringResource(R.string.local_run_usage_unavailable)
                else snapshot?.let {
                    stringResource(if (it.tracked.unreportedRequestCount > 0L) R.string.local_run_usage_incomplete
                        else R.string.local_run_usage_known, it.tracked.totalTokens)
                }
                    ?: stringResource(R.string.local_run_usage_loading),
            onClick = { open = true; taskId = null; requestId = null },
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
        )
    }
    if (open) {
        DsBottomSheet(
            title = stringResource(R.string.local_run_usage_title),
            onDismiss = { open = false },
            scrollable = true,
        ) {
            Text(stringResource(R.string.local_run_usage_retained), style = DsType.small13, color = DsTheme.colors.labelSecondary)
            if (taskId != null) DsButton(stringResource(R.string.common_back), onClick = {
                if (requestId != null) requestId = null else taskId = null
            }, variant = DsButtonVariant.Ghost)
            when {
                loading -> Text(stringResource(R.string.local_run_usage_loading))
                failed || detailFailed -> Text(stringResource(R.string.local_run_usage_unavailable))
                requestId != null -> {
                    UsageRequestDetailPage(request)
                }
                taskId != null -> {
                    UsageGroupDetailPage(detail, onOpenRequest = { requestId = it })
                }
                else -> {
                    snapshot?.tracked?.let { aggregate ->
                        Text(stringResource(R.string.local_run_usage_known, aggregate.totalTokens))
                        if (aggregate.unreportedRequestCount > 0L) {
                            Text(stringResource(R.string.local_run_usage_missing, aggregate.unreportedRequestCount))
                        }
                    }
                    snapshot?.tasks?.take(visibleTasks)?.forEach { task ->
                        DsButton(
                            text = stringResource(R.string.local_run_usage_task, task.title, task.aggregate.totalTokens),
                            onClick = { taskId = task.key },
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                    if ((snapshot?.tasks?.size ?: 0) > visibleTasks) DsButton(
                        stringResource(R.string.local_artifact_more_history),
                        onClick = { visibleTasks += 10 }, variant = DsButtonVariant.Ghost,
                    )
                    if (snapshot?.tasks.isNullOrEmpty()) Text(stringResource(R.string.local_run_usage_no_tasks))
                }
            }
        }
    }
}

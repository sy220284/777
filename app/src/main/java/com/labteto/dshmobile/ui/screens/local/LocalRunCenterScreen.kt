package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.ui.components.DsPageEmptyState
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.rootSurface

internal fun LocalWorkUiState.hasRunCenterContent(): Boolean =
    running ||
        goal != null ||
        workflowProgress != null ||
        todos.isNotEmpty() ||
        jobs.isNotEmpty() ||
        pendingApproval != null ||
        pendingQuestion != null ||
        plan.isNotEmpty() ||
        queuedInputCount > 0 ||
        handoffSummary != null

@Composable
internal fun LocalRunCenterScreen(
    state: LocalWorkUiState,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    onOpenResults: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DsTheme.colors.rootSurface(),
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
            DsTopBar(
                title = stringResource(R.string.local_run_center),
                onBack = onDismiss,
                backContentDescription = stringResource(R.string.common_back),
                largeTitle = true,
                modifier = Modifier.padding(horizontal = DsSpacing.medium),
            )
            if (!state.hasRunCenterContent()) {
                DsPageEmptyState(
                    icon = FeatherIcons.Activity,
                    title = stringResource(R.string.local_run_center_empty_title),
                    body = stringResource(R.string.local_run_center_empty_body),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                ) {
                    ExecutionStatusCard(
                        state = state,
                        onJobOutput = onJobOutput,
                        onStopJob = onStopJob,
                        onOpenResults = onOpenResults,
                        showHeader = false,
                    )
                }
            }
        }
    }
}

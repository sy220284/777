package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.work.LocalAgentTeamMemberUiState
import com.labteto.dshmobile.local.work.LocalAgentTeamTaskUiState
import com.labteto.dshmobile.local.work.LocalAgentTeamUiState
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun LocalAgentSwarmLaunchEntry(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, BackgroundRegion.BOTTOM),
        border = BorderStroke(
            1.dp,
            if (selected) colors.accent.copy(alpha = 0.32f) else colors.borderL2,
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DsSpacing.medium,
                vertical = DsSpacing.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kimi_create_subagent),
                contentDescription = null,
                tint = if (selected) colors.accent else colors.labelPrimary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    stringResource(R.string.local_team_title),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(R.string.local_team_high_usage_tip),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_kimi_check),
                    contentDescription = null,
                    tint = colors.accent,
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelCaption,
                )
            }
        }
    }
}

@Composable
internal fun LocalAgentTeamStatusBar(
    team: LocalAgentTeamUiState,
    launchPending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    AnimatedVisibility(
        visible = launchPending || team.visible,
        enter = fadeIn(DsAnimations.composerFade) +
            expandVertically(animationSpec = DsAnimations.composerReveal),
        exit = fadeOut(DsAnimations.composerFade) +
            shrinkVertically(animationSpec = DsAnimations.composerReveal),
    ) {
        Surface(
            onClick = onClick,
            enabled = team.visible,
            modifier = modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, BackgroundRegion.BOTTOM),
            border = BorderStroke(1.dp, colors.borderL1),
            tonalElevation = 0.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(
                        horizontal = DsSpacing.medium,
                        vertical = DsSpacing.small,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kimi_task_subagent),
                        contentDescription = null,
                        tint = if (teamDotState(team, launchPending) == StateDotState.Running) {
                            colors.accent
                        } else {
                            colors.labelSecondary
                        },
                    )
                    StateDot(teamDotState(team, launchPending), size = 8.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.local_team_title),
                            style = DsType.std14Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                        )
                        Text(
                            teamSummary(team, launchPending),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (team.tasks.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.local_team_progress_compact,
                                team.completedTaskCount,
                                team.tasks.size,
                            ),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                    }
                    if (team.visible) {
                        Icon(
                            FeatherIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.labelCaption,
                        )
                    }
                }
                if (team.visible && team.members.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(
                            start = DsSpacing.medium,
                            end = DsSpacing.medium,
                            bottom = DsSpacing.small,
                        ),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    ) {
                        team.members
                            .sortedBy(::teamMemberPriority)
                            .take(MAX_INLINE_TEAM_MEMBERS)
                            .forEach { member ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                                ) {
                                    StateDot(memberDotState(member), size = 6.dp)
                                    Text(
                                        member.name,
                                        style = DsType.caption11Strong.withReadingWeight(),
                                        color = colors.labelPrimary,
                                        maxLines = 1,
                                    )
                                    Text(
                                        member.currentTask ?: memberStatusLabel(member),
                                        style = DsType.caption11.withReadingWeight(),
                                        color = colors.labelSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        val hidden = team.members.size - MAX_INLINE_TEAM_MEMBERS
                        if (hidden > 0) {
                            Text(
                                stringResource(R.string.local_team_more_assistants, hidden),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalAgentTeamSheet(
    team: LocalAgentTeamUiState,
    onMemberOutput: (String) -> String,
    onSendMemberMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onStopMember: suspend (String) -> LocalWorkUiActionResult,
    onStopAll: suspend () -> LocalWorkUiActionResult,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    val toast = rememberDsToast()
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmStopAll by remember { mutableStateOf(false) }
    var stopAllBusy by remember { mutableStateOf(false) }
    LaunchedEffect(team.members.map { it.id }, selectedMemberId) {
        if (selectedMemberId != null && team.members.none { it.id == selectedMemberId }) {
            selectedMemberId = null
        }
    }

    DsBottomSheet(
        title = stringResource(R.string.local_team_title),
        subtitle = stringResource(
            R.string.local_team_sheet_summary,
            team.members.size,
            team.completedTaskCount,
            team.tasks.size,
        ),
        trailing = {
            if (team.runningMemberCount > 0) {
                DsButton(
                    text = stringResource(R.string.local_team_stop_running),
                    onClick = { confirmStopAll = true },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    icon = FeatherIcons.Square,
                )
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            TeamProgressBlock(team)

            Text(
                stringResource(if (team.provisioningMemberCount > 0) R.string.local_team_recruit_title else R.string.local_team_assistants),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.labelTertiary,
            )
            if (team.members.isEmpty()) {
                Text(
                    stringResource(R.string.local_team_recruiting),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    team.members.sortedBy(::teamMemberPriority).forEach { member ->
                        TeamMemberRow(
                            member = member,
                            selected = member.id == selectedMemberId,
                            onClick = {
                                selectedMemberId =
                                    if (selectedMemberId == member.id) null else member.id
                            },
                        )
                        AnimatedVisibility(
                            visible = member.id == selectedMemberId,
                            enter = fadeIn(DsAnimations.composerFade) +
                                expandVertically(animationSpec = DsAnimations.composerReveal),
                            exit = fadeOut(DsAnimations.composerFade) +
                                shrinkVertically(animationSpec = DsAnimations.composerReveal),
                        ) {
                            TeamMemberDetail(
                                member = member,
                                onMemberOutput = onMemberOutput,
                                onSendMemberMessage = onSendMemberMessage,
                                onStopMember = onStopMember,
                                onFeedback = toast.second,
                            )
                        }
                    }
                }
            }

            if (team.tasks.isNotEmpty()) {
                Text(
                    stringResource(R.string.local_team_tasks),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelTertiary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                    team.tasks.forEach { task -> TeamTaskRow(task) }
                }
            }

            if (team.pendingMessageCount > 0) {
                Text(
                    stringResource(R.string.local_team_pending_messages, team.pendingMessageCount),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.warnLabel,
                )
            }
            team.failure?.takeIf(String::isNotBlank)?.let { failure ->
                Text(
                    stringResource(R.string.local_team_failure, failure),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.error,
                )
            }
        }
        DsToastHost(toast)
    }

    if (confirmStopAll) {
        DsDialog(
            title = stringResource(R.string.local_team_stop_confirm_title),
            onDismiss = { if (!stopAllBusy) confirmStopAll = false },
            dismissOnScrimTap = !stopAllBusy,
        ) {
            Text(
                stringResource(R.string.local_team_stop_confirm_message),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small, Alignment.End),
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmStopAll = false },
                    enabled = !stopAllBusy,
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.local_team_stop_running),
                    onClick = {
                        if (stopAllBusy) return@DsButton
                        stopAllBusy = true
                        scope.launch {
                            val result = try {
                                onStopAll()
                            } finally {
                                stopAllBusy = false
                            }
                            toast.second(result.message)
                            if (result.accepted) confirmStopAll = false
                        }
                    },
                    enabled = !stopAllBusy,
                    loading = stopAllBusy,
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun TeamProgressBlock(team: LocalAgentTeamUiState) {
    val colors = DsTheme.colors
    val progress = if (team.tasks.isEmpty()) 0f else {
        team.completedTaskCount.toFloat() / team.tasks.size.toFloat()
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.local_team_progress),
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (team.tasks.isEmpty()) {
                    teamSummary(team, launchPending = false)
                } else {
                    stringResource(
                        R.string.local_team_progress_count,
                        team.completedTaskCount,
                        team.tasks.size,
                    )
                },
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
        }
        if (team.tasks.isNotEmpty()) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.borderL3,
            )
        }
        if (team.blockedTaskCount > 0) {
            Text(
                stringResource(R.string.local_team_blocked_count, team.blockedTaskCount),
                style = DsType.caption11.withReadingWeight(),
                color = colors.warnLabel,
            )
        }
    }
}

@Composable
private fun TeamMemberRow(
    member: LocalAgentTeamMemberUiState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, BackgroundRegion.BOTTOM),
        border = BorderStroke(
            1.dp,
            if (selected) colors.accent.copy(alpha = 0.32f) else colors.borderL2,
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DsSpacing.medium,
                vertical = DsSpacing.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            StateDot(memberDotState(member), size = 8.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    member.name,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 1,
                )
                Text(
                    member.currentTask
                        ?: member.description.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.local_team_waiting_task),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    memberStatusLabel(member),
                    style = DsType.caption11Strong.withReadingWeight(),
                    color = if (memberDotState(member) == StateDotState.Running) {
                        colors.accent
                    } else {
                        colors.labelTertiary
                    },
                )
                Icon(
                    if (selected) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelCaption,
                )
            }
        }
    }
}

@Composable
private fun TeamMemberDetail(
    member: LocalAgentTeamMemberUiState,
    onMemberOutput: (String) -> String,
    onSendMemberMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onStopMember: suspend (String) -> LocalWorkUiActionResult,
    onFeedback: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(member.id) { mutableStateOf("") }
    var sending by remember(member.id) { mutableStateOf(false) }
    var stopping by remember(member.id) { mutableStateOf(false) }
    var output by remember(member.id) { mutableStateOf("") }

    LaunchedEffect(member.jobId, member.activity) {
        do {
            output = runCatching { onMemberOutput(member.jobId) }.getOrDefault("")
            if (member.activity != "running") break
            delay(900)
        } while (true)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        member.description.takeIf(String::isNotBlank)?.let { description ->
            Text(
                stringResource(R.string.local_team_role_description, description),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelSecondary,
            )
        }
        if (output.isNotBlank()) {
            Text(
                stringResource(R.string.local_team_activity),
                style = DsType.caption11Strong.withReadingWeight(),
                color = colors.labelTertiary,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = DsShapes.row,
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING),
            ) {
                Text(
                    output.takeLast(MAX_TEAM_OUTPUT_CHARS),
                    modifier = Modifier.padding(DsSpacing.small),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            stringResource(R.string.local_team_messages),
            style = DsType.caption11Strong.withReadingWeight(),
            color = colors.labelTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                enabled = !sending,
                singleLine = true,
                placeholder = {
                    Text(
                        stringResource(R.string.local_team_message_hint),
                        style = DsType.small13.withReadingWeight(),
                    )
                },
            )
            DsIconButton(
                icon = FeatherIcons.ArrowUp,
                contentDescription = stringResource(R.string.local_team_send_message),
                enabled = draft.isNotBlank() && !sending,
                selected = draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty() || sending) return@DsIconButton
                    sending = true
                    scope.launch {
                        val result = try {
                            onSendMemberMessage(member.id, text)
                        } finally {
                            sending = false
                        }
                        onFeedback(result.message)
                        if (result.accepted && draft.trim() == text) draft = ""
                    }
                },
            )
        }

        if (member.activity == "running") {
            DsButton(
                text = stringResource(R.string.local_team_stop_member),
                onClick = {
                    if (stopping) return@DsButton
                    stopping = true
                    scope.launch {
                        val result = try {
                            onStopMember(member.id)
                        } finally {
                            stopping = false
                        }
                        onFeedback(result.message)
                    }
                },
                enabled = !stopping,
                loading = stopping,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
                icon = FeatherIcons.Square,
            )
        }

        if (member.pendingMessageCount > 0) {
            Text(
                stringResource(
                    R.string.local_team_member_pending_messages,
                    member.pendingMessageCount,
                ),
                style = DsType.caption11.withReadingWeight(),
                color = colors.warnLabel,
            )
        }
        member.error?.takeIf(String::isNotBlank)?.let { error ->
            Text(
                error,
                style = DsType.caption11.withReadingWeight(),
                color = colors.error,
            )
        }
    }
}

@Composable
private fun TeamTaskRow(task: LocalAgentTeamTaskUiState) {
    val colors = DsTheme.colors
    val blocked = task.status == "pending" && !task.ready && task.blockedByTitles.isNotEmpty()
    val detail = when {
        blocked -> stringResource(
            R.string.local_team_blocked_by,
            task.blockedByTitles.joinToString("、"),
        )
        task.writeConflict -> stringResource(R.string.local_team_write_conflict)
        task.ownerName != null -> stringResource(R.string.local_team_owner, task.ownerName)
        task.description.isNotBlank() -> task.description
        else -> null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.row,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, BackgroundRegion.BOTTOM),
        border = BorderStroke(1.dp, colors.borderL2),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DsSpacing.medium,
                vertical = DsSpacing.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                imageVector = if (task.status == "completed") FeatherIcons.Check else FeatherIcons.CheckSquare,
                contentDescription = null,
                tint = if (task.status == "completed") colors.accent else colors.labelSecondary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    task.subject,
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let {
                    Text(
                        it,
                        style = DsType.caption11.withReadingWeight(),
                        color = if (task.writeConflict || blocked) colors.warnLabel else colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                taskStatusLabel(task),
                style = DsType.caption11Strong.withReadingWeight(),
                color = if (task.status == "in_progress") colors.accent else colors.labelTertiary,
            )
        }
    }
}

@Composable
private fun teamSummary(team: LocalAgentTeamUiState, launchPending: Boolean): String = when {
    launchPending && !team.visible ->
        stringResource(R.string.local_team_assigning_tasks)
    team.provisioningMemberCount > 0 ->
        stringResource(R.string.local_team_recruiting)
    team.runningMemberCount > 0 ->
        stringResource(R.string.local_team_running_count, team.runningMemberCount)
    team.failedMemberCount > 0 && team.tasks.isEmpty() ->
        stringResource(R.string.local_team_failed_count, team.failedMemberCount)
    team.members.isNotEmpty() && team.tasks.isEmpty() ->
        stringResource(R.string.local_team_recruited_count, team.members.size)
    team.tasks.any { task ->
        task.status == "pending" && task.ready && task.ownerName == null
    } ->
        stringResource(R.string.local_team_assigning_members)
    team.blockedTaskCount > 0 ->
        stringResource(R.string.local_team_blocked_count, team.blockedTaskCount)
    team.tasks.isNotEmpty() && team.completedTaskCount == team.tasks.size ->
        stringResource(R.string.local_team_all_completed)
    team.members.isNotEmpty() ->
        stringResource(R.string.local_team_waiting_task)
    else ->
        stringResource(R.string.local_team_matching_members)
}

private fun teamDotState(team: LocalAgentTeamUiState, launchPending: Boolean): StateDotState = when {
    launchPending || team.provisioningMemberCount > 0 || team.runningMemberCount > 0 ->
        StateDotState.Running
    team.failure != null || team.failedMemberCount > 0 ->
        StateDotState.Error
    team.tasks.isNotEmpty() && team.completedTaskCount == team.tasks.size ->
        StateDotState.Done
    team.blockedTaskCount > 0 ->
        StateDotState.Warning
    else ->
        StateDotState.Idle
}

private fun teamMemberPriority(member: LocalAgentTeamMemberUiState): Int = when {
    member.activity == "running" || member.activity == "stopping" -> 0
    member.phase == "provisioning" -> 1
    member.activity == "dormant" || member.activity == "waiting" || member.activity == "active" -> 2
    member.activity == "completed" -> 3
    member.activity == "killed" || member.activity == "cancelled" || member.activity == "interrupted" -> 4
    member.phase == "failed" || member.activity == "failed" -> 5
    else -> 2
}

private fun memberDotState(member: LocalAgentTeamMemberUiState): StateDotState = when {
    member.phase == "failed" || member.activity == "failed" -> StateDotState.Error
    member.phase == "provisioning" -> StateDotState.Running
    member.activity == "running" || member.activity == "stopping" -> StateDotState.Running
    member.activity == "completed" -> StateDotState.Done
    member.activity == "killed" || member.activity == "cancelled" || member.activity == "interrupted" -> StateDotState.Warning
    else -> StateDotState.Idle
}

@Composable
private fun memberStatusLabel(member: LocalAgentTeamMemberUiState): String = stringResource(
    when {
        member.phase == "failed" || member.activity == "failed" -> R.string.local_team_state_failed
        member.phase == "provisioning" -> R.string.local_team_state_booting
        member.activity == "running" || member.activity == "stopping" -> R.string.local_team_state_thinking
        member.activity == "completed" -> R.string.local_team_state_task_done
        member.activity == "killed" -> R.string.local_team_state_dismissed
        member.activity == "cancelled" || member.activity == "interrupted" -> R.string.local_team_state_stopped
        member.activity == "dormant" -> R.string.local_team_state_offwork
        else -> R.string.local_team_state_waiting
    },
)

@Composable
private fun taskStatusLabel(task: LocalAgentTeamTaskUiState): String = stringResource(
    when {
        task.status == "completed" -> R.string.local_team_task_completed
        task.status == "in_progress" -> R.string.local_team_task_running
        !task.ready && task.blockedByTitles.isNotEmpty() -> R.string.local_team_task_blocked
        else -> R.string.local_team_task_pending
    },
)

private const val MAX_INLINE_TEAM_MEMBERS = 2
private const val MAX_TEAM_OUTPUT_CHARS = 2400

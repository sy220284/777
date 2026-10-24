package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric



import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.work.LocalAgentTeamMemberUiState
import com.labteto.dshmobile.local.work.LocalAgentTeamTaskUiState
import com.labteto.dshmobile.local.work.LocalAgentTeamUiState
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.shenyan.SyProgressBar
import com.labteto.dshmobile.ui.shenyan.SyProgressStatus
import com.shenyan.designsystem.components.SyIconButton
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SyToastHost
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.shenyan.rememberSyToast
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Shared team transitions respect reduced motion and custom tempo. */
@Composable
private fun teamFadeSpec() = com.shenyan.designsystem.foundation.SyMotion.run {
    tween<Float>(duration(composerFadeMs,
        com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
        com.shenyan.designsystem.foundation.LocalSyMotionTempo.current), easing = ease)
}

@Composable
private fun teamRevealSpec() = com.shenyan.designsystem.foundation.SyMotion.run {
    tween<IntSize>(duration(composerRevealMs,
        com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
        com.shenyan.designsystem.foundation.LocalSyMotionTempo.current), easing = ease)
}

@Composable
internal fun LocalAgentSwarmLaunchEntry(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = DsShapes.row,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp)
                .padding(horizontal = SyMetric.gapMd, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_create_subagent),
                contentDescription = null,
                tint = if (selected) colors.primary else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.local_team_title),
                style = DsType.std14Strong.withReadingWeight(),
                color = if (selected) colors.primary else colors.text,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_check),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
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
    dismissed: Boolean = false,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    AnimatedVisibility(
        visible = !dismissed && (launchPending || team.visible),
        enter = fadeIn(teamFadeSpec()) +
            expandVertically(animationSpec = teamRevealSpec()),
        exit = fadeOut(teamFadeSpec()) +
            shrinkVertically(animationSpec = teamRevealSpec()),
    ) {
        androidx.compose.material3.Surface(
            onClick = onClick,
            enabled = team.visible,
            modifier = modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(
                        horizontal = SyMetric.gapLg,
                        vertical = SyMetric.gapMd,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_task_subagent),
                        contentDescription = null,
                        tint = if (teamDotState(team, launchPending) == StateDotState.Running) {
                            colors.primary
                        } else {
                            colors.textSecondary
                        },
                    )
                    StateDot(teamDotState(team, launchPending), size = 8.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.local_team_title),
                            style = DsType.std14Strong.withReadingWeight(),
                            color = colors.text,
                        )
                        Text(
                            teamSummary(team, launchPending),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textSecondary,
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
                            color = colors.textTertiary,
                        )
                    }
                    if (team.visible) {
                        Icon(
                            FeatherIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.textTertiary,
                        )
                    }
                }
                if (team.visible && team.members.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(
                            start = SyMetric.gapLg,
                            end = SyMetric.gapLg,
                            bottom = SyMetric.gapMd,
                        ),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    ) {
                        team.members
                            .sortedBy(::teamMemberPriority)
                            .take(MAX_INLINE_TEAM_MEMBERS)
                            .forEach { member ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                                ) {
                                    StateDot(memberDotState(member), size = 6.dp)
                                    Text(
                                        member.friendlyName,
                                        style = DsType.caption11Strong.withReadingWeight(),
                                        color = colors.text,
                                        maxLines = 1,
                                    )
                                    Text(
                                        member.currentTask ?: memberStatusLabel(member),
                                        style = DsType.caption11.withReadingWeight(),
                                        color = colors.textSecondary,
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
                                color = colors.textTertiary,
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
    onLeadFollowup: (String) -> Unit,
    onDismiss: () -> Unit,
    onCollapse: () -> Unit = onDismiss,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val scope = rememberCoroutineScope()
    val toast = rememberSyToast()
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmStopAll by remember { mutableStateOf(false) }
    var stopAllBusy by remember { mutableStateOf(false) }
    LaunchedEffect(team.rebuilding, team.members.map { it.id }, selectedMemberId) {
        if (!team.rebuilding && selectedMemberId != null && team.members.none { it.id == selectedMemberId }) {
            selectedMemberId = null
        }
    }

    SyResponsiveSheet(
        title = stringResource(R.string.local_team_title),
        scrollable = true,
        subtitle = if (team.rebuilding) stringResource(R.string.common_loading) else stringResource(
            R.string.local_team_sheet_summary,
            team.members.size,
            team.completedTaskCount,
            team.tasks.size,
        ),
        trailing = {
            if (team.runningMemberCount > 0) {
                SyButton(
                    text = stringResource(R.string.local_team_stop_running),
                    onClick = { confirmStopAll = true },
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                    icon = FeatherIcons.Square,
                )
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        ) {
            if (team.rebuilding) {
                Text(stringResource(R.string.common_loading), style = DsType.std14Strong, color = colors.textSecondary)
                return@Column
            }
            TeamProgressBlock(team)
            SyButton(
                text = stringResource(R.string.local_team_collapse_after_view),
                onClick = onCollapse,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
            Text(
                stringResource(R.string.local_team_readonly_hint),
                style = DsType.caption11, color = colors.textSecondary,
            )
            SyButton(
                text = stringResource(R.string.local_team_lead_followup),
                onClick = { onLeadFollowup("") },
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )

            Text(
                stringResource(if (team.provisioningMemberCount > 0) R.string.local_team_recruit_title else R.string.local_team_assistants),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.textTertiary,
            )
            if (team.members.isEmpty()) {
                Text(
                    stringResource(R.string.local_team_recruiting),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
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
                            enter = fadeIn(teamFadeSpec()) +
                                expandVertically(animationSpec = teamRevealSpec()),
                            exit = fadeOut(teamFadeSpec()) +
                                shrinkVertically(animationSpec = teamRevealSpec()),
                        ) {
                            TeamMemberDetail(
                                member = member,
                                onMemberOutput = onMemberOutput,
                                onSendMemberMessage = onSendMemberMessage,
                                onStopMember = onStopMember,
                                onFeedback = toast.second,
                                onLeadFollowup = onLeadFollowup,
                            )
                        }
                    }
                }
            }

            if (team.tasks.isNotEmpty()) {
                Text(
                    stringResource(R.string.local_team_tasks),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.textTertiary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                    team.tasks.forEach { task -> TeamTaskRow(task) }
                }
            }

            TeamActivityFeed(team)

            if (team.pendingMessageCount > 0) {
                Text(
                    stringResource(R.string.local_team_pending_messages, team.pendingMessageCount),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.warningInk,
                )
            }
            team.failure?.takeIf(String::isNotBlank)?.let { failure ->
                Text(
                    stringResource(R.string.local_team_failure, failure),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.dangerInk,
                )
            }
        }
        SyToastHost(toast)
    }

    val actionFailedText = stringResource(R.string.local_team_action_failed)

    if (confirmStopAll) {
        SyDialog(
            title = stringResource(R.string.local_team_stop_confirm_title),
            onDismiss = { if (!stopAllBusy) confirmStopAll = false },
            dismissOnScrimTap = !stopAllBusy,
        ) {
            Text(
                stringResource(R.string.local_team_stop_confirm_message),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd, Alignment.End),
            ) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmStopAll = false },
                    enabled = !stopAllBusy,
                    variant = SyButtonVariant.Ghost,
                )
                SyButton(
                    text = stringResource(R.string.local_team_stop_running),
                    onClick = {
                        if (stopAllBusy) return@SyButton
                        stopAllBusy = true
                        scope.launch {
                            val result = try {
                                onStopAll()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                LocalWorkUiActionResult(false, actionFailedText)
                            } finally {
                                stopAllBusy = false
                            }
                            toast.second(result.message)
                            if (result.accepted) confirmStopAll = false
                        }
                    },
                    enabled = !stopAllBusy,
                    loading = stopAllBusy,
                    variant = SyButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun TeamProgressBlock(team: LocalAgentTeamUiState) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val progress = if (team.tasks.isEmpty()) 0f else {
        team.completedTaskCount.toFloat() / team.tasks.size.toFloat()
    }
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.local_team_progress),
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.text,
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
                color = colors.textSecondary,
            )
        }
        if (team.tasks.isNotEmpty()) {
            SyProgressBar(
                value = progress,
                modifier = Modifier.fillMaxWidth(),
                status = when {
                    team.failure != null || team.failedMemberCount > 0 -> SyProgressStatus.Failed
                    team.completedTaskCount == team.tasks.size -> SyProgressStatus.Done
                    else -> SyProgressStatus.Running
                },
            )
        }
        if (team.returnedMemberCount > 0 && team.tasks.isNotEmpty()) {
            Text(
                stringResource(R.string.local_team_results_pending, team.returnedMemberCount),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textSecondary,
            )
        }
        if (team.blockedTaskCount > 0) {
            Text(
                stringResource(R.string.local_team_blocked_count, team.blockedTaskCount),
                style = DsType.caption11.withReadingWeight(),
                color = colors.warningInk,
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val expansionLabel = stringResource(if (selected) R.string.local_team_expanded else R.string.local_team_collapsed)
    val pulse = rememberHapticPulse()
    androidx.compose.material3.Surface(
        onClick = { pulse(); onClick() },
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansionLabel },
        shape = DsShapes.block,
        color = if (selected) colors.selected.copy(alpha = .18f) else androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary.copy(alpha = 0.32f) else colors.border,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SyMetric.gapLg,
                vertical = SyMetric.gapLg,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            StateDot(memberDotState(member), size = 8.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Text(
                    member.friendlyName,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.text,
                    maxLines = 1,
                )
                Text(
                    member.currentTask
                        ?: member.description.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.local_team_waiting_task),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    memberStatusLabel(member),
                    style = DsType.caption11Strong.withReadingWeight(),
                    color = if (memberDotState(member) == StateDotState.Running) {
                        colors.primary
                    } else {
                        colors.textTertiary
                    },
                )
                Icon(
                    if (selected) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp),
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
    onLeadFollowup: (String) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val scope = rememberCoroutineScope()
    val actionFailedText = stringResource(R.string.local_team_action_failed)
    var draft by rememberSaveable(member.id) { mutableStateOf("") }
    var sending by remember(member.id) { mutableStateOf(false) }
    var stopping by remember(member.id) { mutableStateOf(false) }
    var output by remember(member.jobId) { mutableStateOf("") }
    var outputReadFailed by remember(member.jobId) { mutableStateOf(false) }
    var outputRetry by remember(member.jobId) { mutableStateOf(0) }

    // May touch durable job state; keep synchronous reads off the Compose dispatcher.
    LaunchedEffect(member.jobId, member.activity, outputRetry) {
        do {
            try {
                val next = withContext(Dispatchers.IO) { onMemberOutput(member.jobId) }
                output = next
                outputReadFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                outputReadFailed = true
            }
            if (member.activity != "running" && member.activity != "stopping") break
            delay(900)
        } while (true)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        ) {
            member.description.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    stringResource(R.string.local_team_role_description, description),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
            }
            Text(
                stringResource(
                    if (member.mutableToolsEnabled) R.string.local_team_permission_full
                    else R.string.local_team_permission_limited,
                ),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textSecondary,
            )
            if (member.grantedExtensions.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.local_team_permission_grants,
                        member.grantedExtensions.sorted().joinToString("、"),
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.primary,
                )
            }
            TeamMemberWorksite(member, output)
            if (outputReadFailed) {
                Text(
                    stringResource(R.string.local_team_output_read_failed),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.dangerInk,
                )
                SyButton(
                    text = stringResource(R.string.common_retry),
                    onClick = { outputRetry += 1 },
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                )
            }

            Text(
                stringResource(R.string.local_team_messages),
                style = DsType.caption11Strong.withReadingWeight(),
                color = colors.textTertiary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    enabled = member.canReceiveMessage && !sending,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.local_team_message_hint),
                            style = DsType.small13.withReadingWeight(),
                        )
                    },
                )
                SyIconButton(
                    icon = FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.local_team_send_message),
                    enabled = member.canReceiveMessage && draft.isNotBlank() && !sending,
                    selected = draft.isNotBlank(),
                    onClick = {
                        val text = draft.trim()
                        if (!member.canReceiveMessage || text.isEmpty() || sending) return@SyIconButton
                        sending = true
                        scope.launch {
                            val result = try {
                                onSendMemberMessage(member.id, text)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                LocalWorkUiActionResult(false, actionFailedText)
                            } finally {
                                sending = false
                            }
                            onFeedback(result.message)
                            if (result.accepted && draft.trim() == text) draft = ""
                        }
                    },
                )
            }

            if (!member.canReceiveMessage) {
                Text(stringResource(R.string.local_team_message_unavailable), style = DsType.caption11, color = colors.textTertiary)
            }
            if (member.phase == "failed" || member.activity in setOf("failed", "interrupted", "cancelled", "killed", "disabled", "dismissed")) {
                SyButton(
                    text = stringResource(R.string.local_team_lead_recover),
                    onClick = { onLeadFollowup(member.name) },
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                )
            }

            if (member.activity == "running") {
                SyButton(
                    text = stringResource(R.string.local_team_stop_member),
                    onClick = {
                        if (stopping) return@SyButton
                        stopping = true
                        scope.launch {
                            val result = try {
                                onStopMember(member.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                LocalWorkUiActionResult(false, actionFailedText)
                            } finally {
                                stopping = false
                            }
                            onFeedback(result.message)
                        }
                    },
                    enabled = !stopping,
                    loading = stopping,
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
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
                    color = colors.warningInk,
                )
            }
            member.error?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    error,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.dangerInk,
                )
            }
        }
    }
}

@Composable
private fun TeamTaskRow(task: LocalAgentTeamTaskUiState) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val expansionLabel = stringResource(if (expanded) R.string.local_team_expanded else R.string.local_team_collapsed)
    val pulse = rememberHapticPulse()
    val blocked = task.status == "pending" && !task.ready && task.blockedByTitles.isNotEmpty()
    val details = buildList {
        task.ownerName?.let { add(stringResource(R.string.local_team_owner, it)) }
        if (task.blockedByTitles.isNotEmpty()) add(stringResource(R.string.local_team_blocked_by, task.blockedByTitles.joinToString("、")))
        if (task.writeConflict) add(stringResource(R.string.local_team_write_conflict))
        if (task.description.isNotBlank()) add(task.description)
    }
    androidx.compose.material3.Surface(
        onClick = { pulse(); expanded = !expanded },
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansionLabel },
        shape = DsShapes.row,
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                Icon(
                    imageVector = if (task.status == "completed") FeatherIcons.Check else FeatherIcons.CheckSquare,
                    contentDescription = null,
                    tint = if (task.status == "in_progress") colors.primary else colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    task.subject, modifier = Modifier.weight(1f),
                    style = DsType.small13Strong.withReadingWeight(), color = colors.text,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
                Text(taskStatusLabel(task), style = DsType.caption11Strong, color = colors.textTertiary)
                Icon(if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
            }
            if (expanded) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
                        details.forEach { detail ->
                            Text(detail, style = DsType.caption11, color = colors.textSecondary)
                        }
                    }
                }
            } else if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "), style = DsType.caption11,
                    color = if (task.writeConflict || blocked) colors.warningInk else colors.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun teamSummary(team: LocalAgentTeamUiState, launchPending: Boolean): String = when {
    team.rebuilding -> stringResource(R.string.local_team_rebuilding)
    launchPending && !team.visible ->
        stringResource(R.string.local_team_assigning_tasks)
    team.failure != null -> stringResource(R.string.local_team_state_failed)
    team.failedMemberCount > 0 -> stringResource(R.string.local_team_failed_count, team.failedMemberCount)
    team.provisioningMemberCount > 0 ->
        stringResource(R.string.local_team_recruiting)
    team.runningMemberCount > 0 ->
        stringResource(R.string.local_team_running_count, team.runningMemberCount)
    team.stoppingMemberCount > 0 ->
        stringResource(R.string.local_team_stopping_count, team.stoppingMemberCount)
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
    team.rebuilding -> StateDotState.Idle
    team.failure != null || team.failedMemberCount > 0 -> StateDotState.Error
    launchPending || team.provisioningMemberCount > 0 || team.runningMemberCount > 0 ->
        StateDotState.Running
    team.stoppingMemberCount > 0 -> StateDotState.Warning
    team.tasks.isNotEmpty() && team.completedTaskCount == team.tasks.size ->
        StateDotState.Done
    team.blockedTaskCount > 0 ->
        StateDotState.Warning
    else ->
        StateDotState.Idle
}

private fun teamMemberPriority(member: LocalAgentTeamMemberUiState): Int = when {
    member.phase == "disabled" || member.phase == "dismissed" -> 4
    member.awaitingReview -> 2
    member.activity == "running" || member.activity == "stopping" -> 0
    member.phase == "provisioning" -> 1
    member.activity == "idle" || member.activity == "dormant" || member.activity == "waiting" || member.activity == "active" -> 2
    member.activity == "completed" -> 3
    member.activity == "killed" || member.activity == "cancelled" || member.activity == "interrupted" -> 4
    member.phase == "failed" || member.activity == "failed" -> 5
    else -> 2
}

private fun memberDotState(member: LocalAgentTeamMemberUiState): StateDotState = when {
    member.phase == "disabled" || member.phase == "dismissed" -> StateDotState.Warning
    member.awaitingReview -> StateDotState.Idle
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
        member.phase == "disabled" -> R.string.local_team_state_disabled
        member.phase == "dismissed" -> R.string.local_team_state_dismissed
        member.awaitingReview -> R.string.local_team_state_awaiting_review
        member.phase == "failed" || member.activity == "failed" -> R.string.local_team_state_failed
        member.phase == "provisioning" -> R.string.local_team_state_booting
        member.activity == "stopping" -> R.string.local_team_state_stopping
        member.activity == "running" -> R.string.local_team_state_thinking
        member.activity == "completed" -> R.string.local_team_state_task_done
        member.activity == "killed" -> R.string.local_team_state_dismissed
        member.activity == "cancelled" || member.activity == "interrupted" -> R.string.local_team_state_stopped
        member.activity in setOf("idle", "dormant") && member.resultMessageCount > 0 -> R.string.local_team_state_awaiting_review
        member.activity == "idle" || member.activity == "dormant" -> R.string.local_team_state_offwork
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

@Composable
private fun TeamActivityFeed(team: LocalAgentTeamUiState) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (team.activities.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        SyButton(
            text = stringResource(R.string.app_team_activity_feed),
            onClick = { expanded = !expanded },
            variant = SyButtonVariant.Ghost,
            size = SyButtonSize.Small,
            icon = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
        )
        team.activities.takeLast(if (expanded) 12 else 3).forEach { activity ->
            val status = when (activity.status) {
                "provisioning" -> R.string.local_team_recruiting
                "active" -> R.string.app_team_started
                "failed" -> R.string.app_team_failed
                "in_progress" -> R.string.app_team_task_started
                "completed" -> R.string.local_team_task_completed
                "deleted" -> R.string.app_team_task_removed
                "updated" -> R.string.app_team_task_updated
                "queued" -> R.string.app_team_message_queued
                "delivered" -> R.string.app_team_message_delivered
                else -> R.string.app_team_task_created
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                Icon(painterResource(R.drawable.ic_ui_task_subagent), contentDescription = null, tint = colors.textSecondary)
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(activity.memberName?.let { name ->
                            team.members.firstOrNull { it.name == name }?.friendlyName ?: name
                        }, stringResource(status)).joinToString(" · "),
                        style = DsType.small13Strong,
                        color = if (activity.status == "failed") colors.dangerInk else colors.text,
                    )
                    if (activity.title.isNotBlank()) Text(
                        activity.title, style = DsType.caption11, color = colors.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamMemberWorksite(member: LocalAgentTeamMemberUiState, output: String) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable(member.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SyMetric.gapMd), verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                WorkOperationIcon(com.labteto.dshmobile.ui.AgentOperationKind.Delegate, member.activity == "running" || member.phase == "provisioning")
                Text(stringResource(R.string.app_team_worksite), style = DsType.small13Strong, color = colors.text, modifier = Modifier.weight(1f))
            }
            SelectionContainer {
                Text(
                    (if (expanded) output else output.takeLast(1600)).ifBlank { member.error ?: member.description },
                    style = DsType.small13, color = colors.textSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 14,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (output.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                    SyButton(
                        text = stringResource(if (expanded) R.string.local_team_collapse_output else R.string.local_team_expand_output),
                        onClick = { expanded = !expanded },
                        variant = SyButtonVariant.Ghost, size = SyButtonSize.Small,
                    )
                    SyButton(
                        text = stringResource(R.string.common_copy),
                        onClick = { clipboard.setText(AnnotatedString(output)) },
                        variant = SyButtonVariant.Ghost, size = SyButtonSize.Small,
                    )
                }
                Text(stringResource(R.string.local_team_output_scope), style = DsType.caption11, color = colors.textTertiary)
            }
        }
    }
}

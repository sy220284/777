package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.DsSheetChoiceRow
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Screen 02 compact layout: the desktop output rail moves to the existing evidence sheet. */
@Composable
internal fun LocalWorkDesignSurface(
    actions: LocalWorkFeatureUiActions,
    sessionId: String,
    title: String,
    section: Int,
    onSelectSection: (Int) -> Unit,
    onAnswer: (String, String) -> Boolean,
    onCancelQuestion: (String) -> Unit,
    onOpenEvidence: () -> Unit,
    onOpenArtifact: (String) -> Unit,
    execution: @Composable () -> Unit,
) {
    val projected by actions.workState.collectAsStateWithLifecycle()
    // Never show the previous session's asynchronously switching projection.
    val state = projected.takeIf { it.sessionId == sessionId }
    val colors = DsTheme.colors
    var artifacts by remember(sessionId) { mutableStateOf<List<LocalArtifactUiItem>>(emptyList()) }
    var outputError by remember(sessionId) { mutableStateOf(false) }
    var refresh by remember(sessionId) { mutableStateOf(0) }
    LaunchedEffect(sessionId, section, projected.running, projected.todos, refresh) {
        if (section != 4) return@LaunchedEffect
        try {
            artifacts = withContext(Dispatchers.IO) { actions.artifactsForUi(sessionId) }
            outputError = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { outputError = true }
    }
    LaunchedEffect(sessionId, state?.pendingQuestion?.callId, state?.pendingApproval?.callId) {
        if (state?.pendingQuestion != null || state?.pendingApproval != null) onSelectSection(2)
    }
    val labels = listOf(R.string.fluid_work_goal, R.string.fluid_work_plan,
        R.string.fluid_work_decision, R.string.fluid_work_execution, R.string.fluid_work_outputs)
        .map { stringResource(it) }
    val counts = listOf(state?.goal?.let { 1 } ?: 0, state?.plan?.size ?: 0,
        (if (state?.pendingQuestion != null) 1 else 0) + (if (state?.pendingApproval != null) 1 else 0),
        state?.todos?.size ?: 0)
        .map { stringResource(R.string.fluid_item_count, it) } +
        listOf(if (section == 4) stringResource(R.string.fluid_item_count, artifacts.size) else "—")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Column(Modifier.padding(horizontal = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state?.goal?.description?.takeIf(String::isNotBlank) ?: title,
                    modifier = Modifier.weight(1f), style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary)
                DsButton(stringResource(R.string.fluid_tool_output), onOpenEvidence,
                    variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            }
            FluidSectionNavigation(labels, section, onSelectSection, counts = counts, numbered = true,
                attention = if (state?.pendingQuestion != null || state?.pendingApproval != null) 2 else null,
                tag = "workDesignSection")
        }
        if (section == 3) {
            // Keep the established paged event timeline and manual-scroll pause behavior.
            Column(Modifier.weight(1f)) { execution() }
        } else {
            FluidSectionSwap(section, Modifier.weight(1f).fillMaxWidth()) { visibleSection ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                    when (visibleSection) {
                        0 -> DsCard {
                            FluidPanelTitle(stringResource(R.string.fluid_work_goal))
                            Text(state?.goal?.description ?: stringResource(R.string.fluid_goal_empty),
                                style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
                            state?.goal?.let { Text(localGoalStatusLabel(it.status),
                                style = DsType.small13, color = colors.labelSecondary) }
                            state?.workflowProgress?.let { WorkflowProgressSection(it) }
                            state?.handoffSummary?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
                            }
                        }
                        1 -> DsCard {
                            FluidPanelTitle(stringResource(R.string.fluid_work_plan))
                            if (state?.plan.isNullOrEmpty()) Text(stringResource(R.string.fluid_plan_empty),
                                style = DsType.small13, color = colors.labelSecondary)
                            state?.plan?.forEachIndexed { index, step ->
                                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                                    Text((index + 1).toString().padStart(2, '0'), style = DsType.caption11,
                                        color = colors.labelTertiary)
                                    Text(step, style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
                                }
                            }
                            state?.todos?.forEach { todo ->
                                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                                    Text(if (todo.status == "completed") "✓" else "○",
                                        color = if (todo.status == "completed") colors.success else colors.labelTertiary)
                                    Text(todo.content, style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
                                }
                            }
                        }
                        2 -> {
                            state?.pendingQuestion?.let { question ->
                                FluidDecisionPanel(question.callId, question.question, question.options,
                                    onAnswer = { answer ->
                                        onAnswer(question.callId, answer).also { if (it) onSelectSection(3) }
                                    }, onCancel = { onCancelQuestion(question.callId) })
                            }
                            state?.pendingApproval?.let { approval -> DsCard {
                                FluidPanelTitle(stringResource(R.string.local_approval_title))
                                Text(approval.summary, style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
                                Text(stringResource(R.string.fluid_approval_modal_hint), style = DsType.small13,
                                    color = colors.warnLabel)
                            } }
                            if (state?.pendingQuestion == null && state?.pendingApproval == null) DsCard {
                                Text(stringResource(R.string.fluid_decision_empty), style = DsType.std14,
                                    color = colors.labelSecondary)
                                DsButton(stringResource(R.string.fluid_back_execution), { onSelectSection(3) },
                                    variant = DsButtonVariant.Ghost)
                            }
                        }
                        4 -> DsCard {
                            FluidPanelTitle(stringResource(R.string.fluid_work_outputs))
                            if (outputError) {
                                Text(stringResource(R.string.fluid_outputs_failed), color = colors.error)
                                DsButton(stringResource(R.string.common_retry), { refresh++ })
                            } else if (artifacts.isEmpty()) Text(stringResource(R.string.fluid_outputs_empty),
                                style = DsType.std14, color = colors.labelSecondary)
                            artifacts.forEach { artifact ->
                                DsSheetChoiceRow(title = artifact.reference,
                                    subtitle = if (artifact.currentlyAvailable == false)
                                        stringResource(R.string.fluid_output_missing) else artifact.category,
                                    icon = FeatherIcons.FileText,
                                    onClick = { onOpenArtifact(artifact.reference) })
                            }
                            DsButton(stringResource(R.string.fluid_tool_output), onOpenEvidence,
                                variant = DsButtonVariant.Outline, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FluidPanelTitle(title: String) {
    Text(title, style = DsType.std14Strong.withReadingWeight(), color = DsTheme.colors.labelPrimary)
}

/** A decision stays in context; sending is explicit and a rejected submission keeps the draft. */
@Composable
internal fun FluidDecisionPanel(id: String, question: String, options: List<String>,
    onAnswer: (String) -> Boolean, onCancel: () -> Unit) {
    var selection by rememberSaveable(id) { mutableStateOf("") }
    var custom by rememberSaveable(id) { mutableStateOf("") }
    var submitted by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    DsCard {
        FluidPanelTitle(stringResource(R.string.fluid_decision_checkpoint))
        Text(question, style = DsType.std14.withReadingWeight(), color = DsTheme.colors.labelPrimary)
        options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selection == option, enabled = !submitted,
                    onClick = { selection = option; custom = "" })
                DsButton(option, { selection = option; custom = "" }, enabled = !submitted,
                    variant = DsButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
        }
        DsTextField(custom, { custom = it; selection = "" }, enabled = !submitted,
            modifier = Modifier.fillMaxWidth().testTag("workDecisionCustom"),
            label = { Text(stringResource(R.string.local_question_custom_answer)) })
        if (failed) Text(stringResource(R.string.fluid_decision_failed), color = DsTheme.colors.error)
        DsButton(stringResource(R.string.fluid_decision_continue), {
            submitted = onAnswer(custom.trim().ifEmpty { selection })
            failed = !submitted
        }, enabled = !submitted && (custom.isNotBlank() || selection.isNotBlank()),
            modifier = Modifier.fillMaxWidth().testTag("workDecisionContinue"))
        DsButton(stringResource(R.string.common_cancel), onCancel, enabled = !submitted,
            variant = DsButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
    }
}

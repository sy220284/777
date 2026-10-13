package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

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
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.components.SySheetChoiceRow
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.shenyan.designsystem.foundation.LocalSyColors
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Progress and generated files are presented inside the Work conversation. */
@Composable
internal fun LocalWorkDesignSurface(
    actions: LocalWorkFeatureUiActions,
    sessionId: String,
    title: String,
    section: Int,
    onSelectSection: (Int) -> Unit,
    onAnswer: (String, String) -> Boolean,
    onCancelQuestion: (String) -> Unit,
    onOpenArtifact: (String) -> Unit,
    execution: @Composable () -> Unit,
) {
    val projected by actions.workState.collectAsStateWithLifecycle()
    // Never show the previous session's asynchronously switching projection.
    val state = projected.takeIf { it.sessionId == sessionId }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val colors = LocalSyColors.current
    var artifacts by remember(sessionId) { mutableStateOf<List<LocalArtifactUiItem>>(emptyList()) }
    var outputError by remember(sessionId) { mutableStateOf(false) }
    var refresh by remember(sessionId) { mutableStateOf(0) }
    LaunchedEffect(sessionId, section, refresh) {
        if (section != 4) return@LaunchedEffect
        var lastSequence = Long.MIN_VALUE
        while (true) {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val sequence = actions.eventSequenceForUi(sessionId)
                    sequence to if (sequence != lastSequence) actions.artifactsForUi(sessionId) else null
                }
                snapshot.second?.let { artifacts = it }
                lastSequence = snapshot.first
                outputError = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { outputError = true }
            kotlinx.coroutines.delay(750)
        }
    }
    LaunchedEffect(sessionId, state?.pendingQuestion?.callId, state?.pendingApproval?.callId) {
        if (state?.pendingQuestion != null || state?.pendingApproval != null) onSelectSection(2)
    }
    val completionMorphKey = workCompletionCardKey(sessionId, state?.workflowProgress)
    val labels = listOf(R.string.fluid_work_goal, R.string.fluid_work_plan,
        R.string.fluid_work_decision, R.string.fluid_work_execution, R.string.fluid_work_outputs)
        .map { stringResource(it) }
    val counts = listOf(state?.goal?.let { 1 } ?: 0, state?.plan?.size ?: 0,
        (if (state?.pendingQuestion != null) 1 else 0) + (if (state?.pendingApproval != null) 1 else 0),
        )
        .map { stringResource(R.string.fluid_item_count, it) } + listOf("—") +
        listOf(if (section == 4) stringResource(R.string.fluid_item_count, artifacts.size) else "—")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        Column(Modifier.padding(horizontal = SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                Text(state?.goal?.description?.takeIf(String::isNotBlank) ?: title,
                    modifier = Modifier.fillMaxWidth(), style = DsType.base16Strong.withReadingWeight(),
                    color = colors.text)
            }
            FluidSectionNavigation(labels, section, onSelectSection, counts = counts, numbered = true,
                attention = if (state?.pendingQuestion != null || state?.pendingApproval != null) 2 else null,
                tag = "workDesignSection")
        }
        if (section == 3) {
            // Keep the established paged event timeline and manual-scroll pause behavior.
            Column(Modifier.weight(1f)) { execution() }
        } else {
            FluidSectionSwap(section, Modifier.weight(1f).fillMaxWidth(),
                enableSharedBounds = completionMorphKey != null) { visibleSection ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(SyMetric.gapLg), verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg)) {
                    when (visibleSection) {
                        0 -> SyGlassCard(modifier = Modifier.localWorkCompletionSharedBounds(completionMorphKey)) {
                            FluidPanelTitle(stringResource(R.string.fluid_work_goal))
                            Text(state?.goal?.description ?: stringResource(R.string.fluid_goal_empty),
                                style = DsType.std14.withReadingWeight(), color = colors.text)
                            state?.goal?.let { Text(localGoalStatusLabel(it.status),
                                style = DsType.small13, color = colors.textSecondary) }
                            state?.workflowProgress?.let { WorkflowProgressSection(it) }
                            state?.handoffSummary?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
                            }
                        }
                        1 -> SyGlassCard {
                            FluidPanelTitle(stringResource(R.string.fluid_work_plan))
                            if (state?.plan.isNullOrEmpty()) Text(stringResource(R.string.fluid_plan_empty),
                                style = DsType.small13, color = colors.textSecondary)
                            state?.plan?.forEachIndexed { index, step ->
                                Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                                    Text((index + 1).toString().padStart(2, '0'), style = DsType.caption11,
                                        color = colors.textTertiary)
                                    Text(step, style = DsType.std14.withReadingWeight(), color = colors.text)
                                }
                            }
                            state?.todos?.forEach { todo ->
                                Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                                    Text(if (todo.status == "completed") "✓" else "○",
                                        color = if (todo.status == "completed") colors.successInk else colors.textTertiary)
                                    Text(todo.content, style = DsType.std14.withReadingWeight(), color = colors.text)
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
                            state?.pendingApproval?.let { approval -> SyGlassCard {
                                FluidPanelTitle(stringResource(R.string.local_approval_title))
                                Text(approval.summary, style = DsType.std14.withReadingWeight(), color = colors.text)
                                Text(stringResource(R.string.fluid_approval_modal_hint), style = DsType.small13,
                                    color = colors.warningInk)
                            } }
                            if (state?.pendingQuestion == null && state?.pendingApproval == null) SyGlassCard {
                                Text(stringResource(R.string.fluid_decision_empty), style = DsType.std14,
                                    color = colors.textSecondary)
                                SyButton(stringResource(R.string.fluid_back_execution), { onSelectSection(3) },
                                    variant = SyButtonVariant.Ghost)
                            }
                        }
                        4 -> SyGlassCard(modifier = Modifier.localWorkCompletionSharedBounds(completionMorphKey)) {
                            FluidPanelTitle(stringResource(R.string.fluid_work_outputs))
                            if (outputError) {
                                Text(stringResource(R.string.fluid_outputs_failed), color = colors.dangerInk)
                                SyButton(stringResource(R.string.common_retry), { refresh++ })
                            } else if (artifacts.isEmpty()) Text(stringResource(R.string.fluid_outputs_empty),
                                style = DsType.std14, color = colors.textSecondary)
                            artifacts.forEach { artifact ->
                                SySheetChoiceRow(title = artifact.reference,
                                    subtitle = if (artifact.currentlyAvailable == false)
                                        stringResource(R.string.fluid_output_missing) else artifact.category,
                                    icon = FeatherIcons.FileText,
                                    enabled = artifact.currentlyAvailable != false,
                                    onClick = {
                                        when (artifact.category) {
                                            "file" -> onOpenArtifact(artifact.reference)
                                            "link" -> runCatching { uriHandler.openUri(artifact.reference) }
                                                .onFailure { outputError = true }
                                            else -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(artifact.reference))
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FluidPanelTitle(title: String) {
    com.shenyan.designsystem.components.SySectionHeader(title)
}

/** A decision stays in context; sending is explicit and a rejected submission keeps the draft. */
@Composable
internal fun FluidDecisionPanel(id: String, question: String, options: List<String>,
    onAnswer: (String) -> Boolean, onCancel: () -> Unit) {
    var selection by rememberSaveable(id) { mutableStateOf("") }
    var custom by rememberSaveable(id) { mutableStateOf("") }
    var submitted by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    SyGlassCard {
        FluidPanelTitle(stringResource(R.string.fluid_decision_checkpoint))
        Text(question, style = DsType.std14.withReadingWeight(), color = LocalSyColors.current.text)
        options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selection == option, enabled = !submitted,
                    onClick = { selection = option; custom = "" })
                SyButton(option, { selection = option; custom = "" }, enabled = !submitted,
                    variant = SyButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
        }
        SyTextField(custom, { custom = it; selection = "" }, enabled = !submitted,
            modifier = Modifier.fillMaxWidth().testTag("workDecisionCustom"),
            label = { Text(stringResource(R.string.local_question_custom_answer)) })
        if (failed) Text(stringResource(R.string.fluid_decision_failed), color = LocalSyColors.current.dangerInk)
        SyButton(stringResource(R.string.fluid_decision_continue), {
            submitted = onAnswer(custom.trim().ifEmpty { selection })
            failed = !submitted
        }, enabled = !submitted && (custom.isNotBlank() || selection.isNotBlank()),
            modifier = Modifier.fillMaxWidth().testTag("workDecisionContinue"))
        SyButton(stringResource(R.string.common_cancel), onCancel, enabled = !submitted,
            variant = SyButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
    }
}

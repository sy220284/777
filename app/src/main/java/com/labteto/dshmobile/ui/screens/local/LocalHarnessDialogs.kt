package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalApprovalImpact
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.agentApprovalPurposeRes
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

@Composable
internal fun NewSessionModeDialog(
    onDismiss: () -> Unit,
    onSelect: (LocalConversationMode) -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = stringResource(R.string.local_new_session_dialog_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.local_new_session_dialog_intro),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        DsButton(
            text = stringResource(R.string.local_new_session_continue),
            onClick = { onSelect(LocalConversationMode.CONTINUATION) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.local_new_session_continue_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        DsButton(
            text = stringResource(R.string.local_new_session_project),
            onClick = { onSelect(LocalConversationMode.PROJECT) },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
        Text(
            stringResource(R.string.local_new_session_project_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        DsButton(
            text = stringResource(R.string.local_new_session_independent),
            onClick = { onSelect(LocalConversationMode.INDEPENDENT) },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
        Text(
            stringResource(R.string.local_new_session_independent_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
    }
}

@Composable
internal fun ApprovalDialog(
    approval: LocalApproval,
    safeAutoApprovalEnabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAutoApprove: () -> Unit,
    onApproveDeviceTurn: () -> Unit,
) {
    val colors = DsTheme.colors
    val impactLabel = stringResource(
        when (approval.impact) {
            LocalApprovalImpact.LOW -> R.string.local_approval_impact_low
            LocalApprovalImpact.MEDIUM -> R.string.local_approval_impact_medium
            LocalApprovalImpact.HIGH -> R.string.local_approval_impact_high
            LocalApprovalImpact.CRITICAL -> R.string.local_approval_impact_critical
        },
    )
    DsDialog(title = stringResource(R.string.local_approval_title), onDismiss = onDeny) {
        Text(
            stringResource(agentOperationLabelRes(approval.toolName)),
            style = DsType.base16Strong,
            color = colors.labelPrimary,
        )
        Text(
            stringResource(R.string.local_approval_impact, impactLabel),
            style = DsType.caption11Strong,
            color = when (approval.impact) {
                LocalApprovalImpact.LOW -> colors.labelTertiary
                LocalApprovalImpact.MEDIUM -> colors.warnLabel
                LocalApprovalImpact.HIGH,
                LocalApprovalImpact.CRITICAL -> colors.error
            },
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.bgModulePlatform,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    stringResource(R.string.local_approval_purpose_title),
                    style = DsType.small13Strong,
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(agentApprovalPurposeRes(approval.toolName)),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
            }
        }

        Text(
            stringResource(
                when {
                    approval.canAutoApproveSafely -> R.string.local_approval_safe_scope
                    approval.canApproveDeviceTurn -> R.string.local_approval_device_scope
                    else -> R.string.local_approval_high_risk_scope
                },
            ),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )

        DsButton(
            stringResource(R.string.local_approval_once),
            onApprove,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            stringResource(R.string.local_approval_reject),
            onDeny,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
        if (!safeAutoApprovalEnabled) {
            DsButton(
                stringResource(R.string.local_approval_enable_safe),
                onAutoApprove,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        } else {
            Text(
                stringResource(R.string.local_approval_safe_enabled),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        if (approval.canApproveDeviceTurn) {
            DsButton(
                stringResource(R.string.local_approval_device_turn),
                onApproveDeviceTurn,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}

@Composable
internal fun QuestionDialog(
    question: String,
    options: List<String>,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var answer by rememberSaveable(question) { mutableStateOf("") }
    DsDialog(
        title = stringResource(R.string.local_question_title),
        onDismiss = onDismiss,
    ) {
        Text(question, style = DsType.base16Strong, color = colors.labelPrimary)
        options.forEach { option ->
            DsButton(
                text = option,
                onClick = { onAnswer(option) },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Outline,
            )
        }
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_question_custom_answer)) },
            maxLines = 4,
        )
        DsButton(
            text = stringResource(R.string.local_question_submit),
            onClick = { onAnswer(answer) },
            modifier = Modifier.fillMaxWidth(),
            enabled = answer.isNotBlank(),
        )
    }
}


@Composable
internal fun ChatPersonaDialog(
    profile: PersonaProfile,
    onSave: (PersonaProfile) -> Unit,
    onAutoFill: suspend (String) -> Result<PersonaProfile>,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.name) }
    var identity by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.identity) }
    var background by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.background) }
    var personality by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.personality) }
    var speechStyle by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.speechStyle) }
    var relationship by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.relationship) }
    var worldSetting by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.worldSetting) }
    var constraints by rememberSaveable(profile.id, profile.updatedAt) {
        mutableStateOf(profile.hardConstraints.joinToString("\n"))
    }
    var examples by rememberSaveable(profile.id, profile.updatedAt) {
        mutableStateOf(profile.exampleDialogues.joinToString("\n"))
    }
    var banned by rememberSaveable(profile.id, profile.updatedAt) {
        mutableStateOf(profile.bannedPhrases.joinToString("\n"))
    }
    var signature by rememberSaveable(profile.id, profile.updatedAt) {
        mutableStateOf(profile.signaturePhrases.joinToString("\n"))
    }
    var aiDescription by rememberSaveable(profile.id) { mutableStateOf("") }
    var aiGenerating by remember(profile.id) { mutableStateOf(false) }
    var aiSucceeded by remember(profile.id) { mutableStateOf(false) }
    var aiError by remember(profile.id) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun lines(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    fun applyGenerated(generated: PersonaProfile) {
        name = generated.name
        identity = generated.identity
        background = generated.background
        personality = generated.personality
        speechStyle = generated.speechStyle
        relationship = generated.relationship
        worldSetting = generated.worldSetting
        constraints = generated.hardConstraints.joinToString("\n")
        examples = generated.exampleDialogues.joinToString("\n")
        banned = generated.bannedPhrases.joinToString("\n")
        signature = generated.signaturePhrases.joinToString("\n")
    }

    DsDialog(title = stringResource(R.string.local_persona_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.local_persona_intro),
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DsTheme.colors.bgModulePlatform,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Text(
                    stringResource(R.string.local_persona_ai_title),
                    style = DsType.base16Strong,
                    color = DsTheme.colors.labelPrimary,
                )
                Text(
                    stringResource(R.string.local_persona_ai_hint),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
                OutlinedTextField(
                    value = aiDescription,
                    onValueChange = { aiDescription = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_persona_ai_description)) },
                    placeholder = { Text(stringResource(R.string.local_persona_ai_example)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !aiGenerating,
                )
                DsButton(
                    text = stringResource(
                        if (aiGenerating) R.string.local_persona_ai_generating
                        else R.string.local_persona_ai_generate
                    ),
                    onClick = {
                        if (aiGenerating) return@DsButton
                        aiGenerating = true
                        aiSucceeded = false
                        aiError = null
                        coroutineScope.launch {
                            onAutoFill(aiDescription)
                                .onSuccess { generated ->
                                    applyGenerated(generated)
                                    aiSucceeded = true
                                }
                                .onFailure { error ->
                                    aiError = error.message ?: "persona_autofill_failed"
                                }
                            aiGenerating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !aiGenerating,
                )
                if (aiGenerating) {
                    Text(
                        stringResource(R.string.local_persona_ai_working_hint),
                        style = DsType.caption11,
                        color = DsTheme.colors.labelTertiary,
                    )
                } else if (aiSucceeded) {
                    Text(
                        stringResource(R.string.local_persona_ai_synced),
                        style = DsType.caption11,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
                aiError?.takeIf(String::isNotBlank)?.let { error ->
                    val message = when (error) {
                        "persona_autofill_busy" -> stringResource(R.string.local_persona_ai_busy)
                        "persona_autofill_unconfigured" -> stringResource(R.string.local_persona_ai_unconfigured)
                        "persona_autofill_failed" -> stringResource(R.string.local_persona_ai_failed)
                        else -> error
                    }
                    Text(
                        message,
                        style = DsType.caption11,
                        color = DsTheme.colors.error,
                    )
                }
            }
        }

        PersonaTextField(stringResource(R.string.local_persona_name), name, { name = it }, singleLine = true)
        PersonaTextField(stringResource(R.string.local_persona_identity), identity, { identity = it })
        PersonaTextField(stringResource(R.string.local_persona_background), background, { background = it })
        PersonaTextField(stringResource(R.string.local_persona_personality), personality, { personality = it })
        PersonaTextField(stringResource(R.string.local_persona_speech_style), speechStyle, { speechStyle = it })
        PersonaTextField(stringResource(R.string.local_persona_relationship), relationship, { relationship = it })
        PersonaTextField(stringResource(R.string.local_persona_world_setting), worldSetting, { worldSetting = it })
        PersonaTextField(stringResource(R.string.local_persona_constraints), constraints, { constraints = it })
        PersonaTextField(stringResource(R.string.local_persona_examples), examples, { examples = it })
        PersonaTextField(stringResource(R.string.local_persona_banned), banned, { banned = it })
        PersonaTextField(stringResource(R.string.local_persona_signature), signature, { signature = it })
        DsButton(
            text = stringResource(R.string.local_persona_save),
            onClick = {
                onSave(
                    profile.copy(
                        name = name,
                        identity = identity,
                        background = background,
                        personality = personality,
                        speechStyle = speechStyle,
                        relationship = relationship,
                        worldSetting = worldSetting,
                        hardConstraints = lines(constraints),
                        exampleDialogues = lines(examples),
                        bannedPhrases = lines(banned),
                        signaturePhrases = lines(signature),
                    ),
                )
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank() && !aiGenerating,
        )
    }
}


@Composable
private fun PersonaTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 6,
    )
}

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
import androidx.compose.runtime.saveable.rememberSaveable
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

    fun lines(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    DsDialog(title = "角色人设", onDismiss = onDismiss) {
        Text(
            "固定人设会在每轮聊天重新注入，不跟普通聊天历史一起被压缩。",
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )
        PersonaTextField("角色名称", name, { name = it }, singleLine = true)
        PersonaTextField("人物身份", identity, { identity = it })
        PersonaTextField("背景经历", background, { background = it })
        PersonaTextField("核心性格", personality, { personality = it })
        PersonaTextField("说话方式", speechStyle, { speechStyle = it })
        PersonaTextField("与我的关系", relationship, { relationship = it })
        PersonaTextField("世界设定", worldSetting, { worldSetting = it })
        PersonaTextField("不可违反的人设（每行一条）", constraints, { constraints = it })
        PersonaTextField("对白参考（每行一条）", examples, { examples = it })
        PersonaTextField("角色专属禁用词（每行一条）", banned, { banned = it })
        PersonaTextField("角色常用表达（每行一条）", signature, { signature = it })
        DsButton(
            text = "保存人设",
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
            enabled = name.isNotBlank(),
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

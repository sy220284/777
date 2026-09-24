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

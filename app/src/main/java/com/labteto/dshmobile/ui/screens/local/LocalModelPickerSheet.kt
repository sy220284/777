package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant

@Composable
internal fun LocalModelPickerSheet(
    profiles: List<LocalModelProfile>,
    currentModel: String,
    currentBaseUrl: String,
    onSelect: (String) -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chatGptPlanLabel = stringResource(R.string.chatgpt_plan_usage)
    DsBottomSheet(title = stringResource(R.string.models_title), onDismiss = onDismiss) {
        profiles.forEach { profile ->
            DsButton(
                text = buildString {
                    append(profile.displayName ?: profile.model)
                    append("  ·  ")
                    append(
                        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) chatGptPlanLabel
                        else profile.baseUrl.substringAfter("://").substringBefore('/'),
                    )
                },
                onClick = {
                    onSelect(profile.id)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                variant = if (profile.model == currentModel && profile.baseUrl == currentBaseUrl)
                    DsButtonVariant.Info else DsButtonVariant.Ghost,
            )
        }
        DsButton(
            text = stringResource(R.string.local_manage_model_config),
            onClick = {
                onDismiss()
                onConfigure()
            },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
    }
}

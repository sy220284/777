package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant

@Composable
internal fun LocalModelPickerSheet(
    profiles: List<LocalModelProfile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chatGptPlanLabel = stringResource(R.string.chatgpt_plan_usage)
    val chatGptAccountLabel = stringResource(R.string.chatgpt_account_short)
    DsBottomSheet(title = stringResource(R.string.models_title), onDismiss = onDismiss) {
        profiles.forEach { profile ->
            DsButton(
                text = buildString {
                    append(profile.displayName ?: profile.model)
                    append("  ·  ")
                    append(
                        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
                            val account = profile.credentialRef?.takeLast(6).orEmpty()
                            if (account.isBlank()) chatGptPlanLabel
                            else "$chatGptPlanLabel · $chatGptAccountLabel $account"
                        } else {
                            profile.baseUrl.substringAfter("://").substringBefore('/')
                        },
                    )
                },
                onClick = {
                    onSelect(profile.id)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                variant = if (profile.id == activeProfileId) DsButtonVariant.Info else DsButtonVariant.Ghost,
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

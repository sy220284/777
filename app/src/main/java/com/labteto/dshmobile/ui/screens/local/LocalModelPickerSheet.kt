package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Kimi-style model picker: models are readable rows, the active route is a lightweight check,
 * and configuration remains a secondary action at the bottom.
 */
@Composable
internal fun LocalModelPickerSheet(
    profiles: List<LocalModelProfile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val chatGptPlanLabel = stringResource(R.string.chatgpt_plan_usage)
    val chatGptAccountLabel = stringResource(R.string.chatgpt_account_short)

    DsBottomSheet(
        title = stringResource(R.string.models_title),
        onDismiss = onDismiss,
    ) {
        DsGroupCard {
            profiles.forEach { profile ->
                val subtitle = if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
                    val account = profile.credentialRef?.takeLast(6).orEmpty()
                    if (account.isBlank()) chatGptPlanLabel
                    else "$chatGptPlanLabel · $chatGptAccountLabel $account"
                } else {
                    profile.baseUrl.substringAfter("://").substringBefore('/')
                }
                val selected = profile.id == activeProfileId
                DsCategoryRow(
                    icon = FeatherIcons.Activity,
                    title = profile.displayName ?: profile.model,
                    subtitle = subtitle,
                    onClick = {
                        onSelect(profile.id)
                        onDismiss()
                    },
                    trailing = if (selected) {
                        {
                            Icon(
                                imageVector = FeatherIcons.CheckSquare,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                )
            }
        }

        DsButton(
            text = stringResource(R.string.local_manage_model_config),
            onClick = {
                onDismiss()
                onConfigure()
            },
            variant = DsButtonVariant.Ghost,
        )
    }
}

package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_USAGE_URL
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

@Composable
internal fun ChatGptPlanUsageBar(profile: LocalModelProfile?) {
    if (profile?.authKind != LocalModelAuthKind.CHATGPT_PLAN) return
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        DsPill(text = stringResource(R.string.chatgpt_using_plan), selected = true)
        Spacer(Modifier.weight(1f))
        DsButton(
            text = stringResource(R.string.chatgpt_manage_usage),
            onClick = { uriHandler.openUri(CHATGPT_USAGE_URL) },
            size = DsButtonSize.Small,
            variant = DsButtonVariant.Ghost,
        )
    }
}

internal fun isChatGptPlanUsageError(message: String?): Boolean {
    val value = message.orEmpty()
    return value.contains("ChatGPT 套餐用量") ||
        value.contains("CHATGPT_PLAN_LIMIT_REACHED") ||
        value.contains("CHATGPT_PLAN_USAGE_UNAVAILABLE")
}

@Composable
internal fun LocalConversationErrorCard(
    sendRejectMessage: String?,
    stateError: String?,
    restoreRequest: String?,
    onRestoreRequest: (String) -> Unit,
    onSwitchModelSource: () -> Unit,
) {
    val message = sendRejectMessage ?: stateError ?: return
    val uriHandler = LocalUriHandler.current
    val usageError = sendRejectMessage == null && isChatGptPlanUsageError(stateError)
    val colors = DsTheme.colors

    Surface(
        color = colors.warnTertiary,
        shape = DsShapes.block,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                message,
                style = DsType.small13,
                color = colors.error,
            )
            when {
                usageError -> {
                    DsButton(
                        text = stringResource(R.string.chatgpt_manage_usage),
                        onClick = { uriHandler.openUri(CHATGPT_USAGE_URL) },
                        modifier = Modifier.fillMaxWidth(),
                        size = DsButtonSize.Small,
                    )
                    DsButton(
                        text = stringResource(R.string.chatgpt_switch_model_source),
                        onClick = onSwitchModelSource,
                        modifier = Modifier.fillMaxWidth(),
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
                sendRejectMessage == null && restoreRequest != null -> {
                    DsButton(
                        text = stringResource(R.string.local_restore_request),
                        onClick = { onRestoreRequest(restoreRequest) },
                        modifier = Modifier.fillMaxWidth(),
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
            }
        }
    }
}

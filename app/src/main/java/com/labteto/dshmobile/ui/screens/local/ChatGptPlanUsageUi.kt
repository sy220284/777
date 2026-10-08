package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
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
import com.labteto.dshmobile.ui.theme.withReadingWeight

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
        value.contains("ChatGPT 暂时无法确认套餐可用量") ||
        value.contains("CHATGPT_PLAN_LIMIT_REACHED") ||
        value.contains("CHATGPT_PLAN_USAGE_UNAVAILABLE")
}

internal fun isChatGptModelRecoveryError(message: String?): Boolean =
    message.orEmpty().let {
        it.contains("ChatGPT 模型已不可用") ||
            it.contains("ChatGPT 账户授权") ||
            it.contains("ChatGPT 套餐请求未通过身份或授权校验") ||
            it.contains("ChatGPT 套餐请求被权限") ||
            it.contains("当前 ChatGPT 用户、工作区或策略") ||
            it.contains("ChatGPT 订阅者上下文未通过验证") ||
            it.contains("当前 ChatGPT 授权上下文") ||
            it.contains("模型来源与请求不一致")
    }

@Composable
internal fun LocalConversationErrorCard(
    sendRejectMessage: String?,
    stateError: String?,
    restoreRequest: String?,
    onRestoreRequest: (String) -> Unit,
    onSwitchModelSource: () -> Unit,
    showConnectAction: Boolean = false,
) {
    val message = sendRejectMessage ?: stateError ?: return
    val uriHandler = LocalUriHandler.current
    val usageError = sendRejectMessage == null && isChatGptPlanUsageError(stateError)
    val modelRecovery = sendRejectMessage == null && isChatGptModelRecoveryError(stateError)
    val colors = DsTheme.colors

    Surface(
        color = colors.warnTertiary,
        shape = DsShapes.block,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        ) {
            Text(
                message,
                style = DsType.small13.withReadingWeight(),
                color = colors.error,
            )
            when {
                showConnectAction -> {
                    DsButton(
                        text = stringResource(R.string.local_welcome_connect_action),
                        onClick = onSwitchModelSource,
                        modifier = Modifier.fillMaxWidth(),
                        size = DsButtonSize.Small,
                    )
                }
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
                modelRecovery -> {
                    DsButton(
                        text = stringResource(R.string.chatgpt_switch_model_source),
                        onClick = onSwitchModelSource,
                        modifier = Modifier.fillMaxWidth(),
                        size = DsButtonSize.Small,
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

package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

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
            it.contains("CHATGPT_PLAN_UNSUPPORTED_CAPABILITY") ||
            it.contains("CHATGPT_PLAN_ROUTE_NOT_SUPPORTED") ||
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
    val usageError = sendRejectMessage == null && isChatGptPlanUsageError(stateError)
    val modelRecovery = sendRejectMessage == null && isChatGptModelRecoveryError(stateError)
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current

    Surface(
        color = colors.warning.copy(alpha = .18f),
        shape = DsShapes.block,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SyMetric.gapLg),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
        ) {
            Text(
                message,
                style = DsType.small13.withReadingWeight(),
                color = colors.dangerInk,
            )
            when {
                showConnectAction -> {
                    SyButton(
                        text = stringResource(R.string.local_welcome_connect_action),
                        onClick = onSwitchModelSource,
                        modifier = Modifier.fillMaxWidth(),
                        size = SyButtonSize.Small,
                    )
                }
                usageError -> {
                    SyButton(
                        text = stringResource(R.string.chatgpt_switch_model_source),
                        onClick = onSwitchModelSource,
                        modifier = Modifier.fillMaxWidth(),
                        size = SyButtonSize.Small,
                        variant = SyButtonVariant.Ghost,
                    )
                }
                modelRecovery -> {
                    SyButton(
                        text = stringResource(R.string.chatgpt_switch_model_source),
                        onClick = onSwitchModelSource,
                        modifier = Modifier.fillMaxWidth(),
                        size = SyButtonSize.Small,
                    )
                }
                sendRejectMessage == null && restoreRequest != null -> {
                    SyButton(
                        text = stringResource(R.string.local_restore_request),
                        onClick = { onRestoreRequest(restoreRequest) },
                        modifier = Modifier.fillMaxWidth(),
                        size = SyButtonSize.Small,
                        variant = SyButtonVariant.Ghost,
                    )
                }
            }
        }
    }
}

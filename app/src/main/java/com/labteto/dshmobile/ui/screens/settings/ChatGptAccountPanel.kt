package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_USAGE_URL
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

@Composable
internal fun ChatGptAccountPanel(
    state: ChatGptUiState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val welcomePrefs = remember(context) {
        context.getSharedPreferences("chatgpt_plan_ui", android.content.Context.MODE_PRIVATE)
    }
    var showWelcome by remember { mutableStateOf(false) }
    val accountShortLabel = stringResource(R.string.chatgpt_account_short)
    val selected = state.selectedAccount
    LaunchedEffect(state.connected) {
        if (state.connected && !welcomePrefs.getBoolean("plan_welcome_seen_v1", false)) {
            showWelcome = true
        }
    }

    val busy = state.phase in setOf(
        ChatGptAuthPhase.PREPARING,
        ChatGptAuthPhase.WAITING_FOR_BROWSER,
        ChatGptAuthPhase.EXCHANGING_CODE,
        ChatGptAuthPhase.VALIDATING_IDENTITY,
        ChatGptAuthPhase.LOADING_MODELS,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.chatgpt_account_title),
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                    )
                    Text(
                        stringResource(R.string.chatgpt_account_hint),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                    )
                }
                DsStatusPill(
                    state = when {
                        selected?.sharingEnabled == true && state.phase == ChatGptAuthPhase.CONNECTED -> DsStatus.Done
                        state.phase == ChatGptAuthPhase.ERROR -> DsStatus.Failed
                        else -> DsStatus.Neutral
                    },
                    label = when {
                        selected?.sharingEnabled == true && state.phase == ChatGptAuthPhase.CONNECTED ->
                            stringResource(R.string.chatgpt_connected)
                        busy -> stringResource(R.string.chatgpt_connecting)
                        else -> stringResource(R.string.chatgpt_not_connected)
                    },
                )
            }

            if (selected == null) {
                Text(
                    stringResource(R.string.chatgpt_privacy_hint),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
                DsButton(
                    text = stringResource(R.string.chatgpt_continue),
                    onClick = {
                        viewModel.connectChatGpt { error ->
                            error?.let(report)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    selected.displayName?.takeIf(String::isNotBlank)
                        ?: selected.email?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.chatgpt_account_fallback),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                )
                selected.email?.takeIf { it != selected.displayName }?.let {
                    Text(it, style = DsType.caption11, color = colors.labelTertiary)
                }
                Text(
                    "$accountShortLabel ${selected.id.takeLast(6)}",
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsPill(text = stringResource(R.string.chatgpt_plan_usage))
                    DsPill(text = stringResource(R.string.chatgpt_model_count, state.models.size))
                }

                if (state.accounts.size > 1) {
                    Text(
                        stringResource(R.string.chatgpt_saved_accounts),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                    state.accounts.forEach { account ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            Text(
                                buildString {
                                    append(
                                        account.displayName?.takeIf(String::isNotBlank)
                                            ?: account.email.orEmpty().ifBlank {
                                                context.getString(R.string.chatgpt_account_fallback)
                                            },
                                    )
                                    append(" · ")
                                    append(accountShortLabel)
                                    append(' ')
                                    append(account.id.takeLast(6))
                                },
                                style = DsType.small13,
                                color = colors.labelSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (account.id == state.selectedAccountId) {
                                DsPill(text = stringResource(R.string.chatgpt_current_account), selected = true)
                            } else {
                                DsButton(
                                    text = stringResource(R.string.chatgpt_use_account),
                                    onClick = {
                                        viewModel.selectChatGptAccount(account.id) { error ->
                                            error?.let(report)
                                        }
                                    },
                                    enabled = !busy,
                                    size = DsButtonSize.Small,
                                    variant = DsButtonVariant.Ghost,
                                )
                            }
                        }
                    }
                }

                DsButton(
                    text = stringResource(R.string.chatgpt_manage_usage),
                    onClick = { uriHandler.openUri(CHATGPT_USAGE_URL) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    DsButton(
                        text = stringResource(R.string.chatgpt_reauthorize),
                        onClick = {
                            viewModel.connectChatGpt(selected.id) { error ->
                                error?.let(report)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Outline,
                    )
                    DsButton(
                        text = stringResource(R.string.chatgpt_disconnect),
                        onClick = {
                            viewModel.disconnectChatGptAccount(selected.id) { error ->
                                error?.let(report)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
                DsButton(
                    text = stringResource(R.string.chatgpt_add_account),
                    onClick = {
                        viewModel.connectChatGpt { error ->
                            error?.let(report)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }

            if (busy) {
                Text(
                    when (state.phase) {
                        ChatGptAuthPhase.WAITING_FOR_BROWSER -> stringResource(R.string.chatgpt_waiting_browser)
                        ChatGptAuthPhase.EXCHANGING_CODE -> stringResource(R.string.chatgpt_exchanging)
                        ChatGptAuthPhase.VALIDATING_IDENTITY -> stringResource(R.string.chatgpt_validating)
                        ChatGptAuthPhase.LOADING_MODELS -> stringResource(R.string.chatgpt_loading_models)
                        else -> stringResource(R.string.chatgpt_connecting)
                    },
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                )
            }
            state.error?.let {
                Text(it, style = DsType.caption11, color = colors.error)
            }
        }
    }

    if (showWelcome) {
        DsDialog(
            title = stringResource(R.string.chatgpt_welcome_title),
            onDismiss = {
                welcomePrefs.edit().putBoolean("plan_welcome_seen_v1", true).apply()
                showWelcome = false
            },
        ) {
            Text(
                stringResource(R.string.chatgpt_welcome_body),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            DsButton(
                text = stringResource(R.string.chatgpt_welcome_confirm),
                onClick = {
                    welcomePrefs.edit().putBoolean("plan_welcome_seen_v1", true).apply()
                    showWelcome = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

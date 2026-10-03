package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_USAGE_URL
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

@Composable
internal fun ChatGptAccountPanel(
    state: ChatGptUiState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
    modelIdentityLocked: Boolean = false,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val welcomePrefs = remember(context) {
        context.getSharedPreferences("chatgpt_plan_ui", android.content.Context.MODE_PRIVATE)
    }
    var showWelcome by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var removeAccountId by remember { mutableStateOf<String?>(null) }
    val accountShortLabel = stringResource(R.string.chatgpt_account_short)
    val accountFallbackLabel = stringResource(R.string.chatgpt_account_fallback)
    val useAccountLabel = stringResource(R.string.chatgpt_use_account)
    val reauthorizeLabel = stringResource(R.string.chatgpt_reauthorize)
    val removeRegistrationLabel = stringResource(R.string.chatgpt_remove_registration)
    val accountActionsLabel = stringResource(R.string.chatgpt_account_actions)
    val selected = state.selectedAccount
    val otherAccounts = state.accounts.filterNot { it.id == state.selectedAccountId }

    LaunchedEffect(state.connected) {
        if (state.connected && !welcomePrefs.getBoolean("plan_welcome_seen_v1", false)) {
            showWelcome = true
        }
    }
    LaunchedEffect(selected?.id) {
        testing = false
        testStatus = null
    }

    val busy = state.phase in setOf(
        ChatGptAuthPhase.PREPARING,
        ChatGptAuthPhase.WAITING_FOR_BROWSER,
        ChatGptAuthPhase.EXCHANGING_CODE,
        ChatGptAuthPhase.VALIDATING_IDENTITY,
        ChatGptAuthPhase.LOADING_MODELS,
    )
    val authorizationCanRestart = state.phase == ChatGptAuthPhase.PREPARING ||
        state.phase == ChatGptAuthPhase.WAITING_FOR_BROWSER
    val accountMutationEnabled = !busy && !modelIdentityLocked

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
                        style = DsType.std14Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    Text(
                        stringResource(R.string.chatgpt_account_hint),
                        style = DsType.caption11.withReadingWeight(),
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
                        state.phase == ChatGptAuthPhase.UNVERIFIED -> stringResource(R.string.chatgpt_authorized_unverified)
                        busy -> stringResource(R.string.chatgpt_connecting)
                        else -> stringResource(R.string.chatgpt_not_connected)
                    },
                )
            }

            if (selected == null) {
                Text(
                    stringResource(R.string.chatgpt_privacy_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
                DsButton(
                    text = stringResource(R.string.chatgpt_continue),
                    onClick = { viewModel.connectChatGpt { error -> error?.let(report) } },
                    enabled = accountMutationEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    selected.displayName?.takeIf(String::isNotBlank)
                        ?: selected.email?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.chatgpt_account_fallback),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                selected.email?.takeIf { it != selected.displayName }?.let {
                    Text(it, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$accountShortLabel ${selected.id.takeLast(6)}",
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    if (accountMutationEnabled) {
                        DsMenu(
                            anchor = {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = accountActionsLabel,
                                    tint = colors.labelSecondary,
                                    modifier = Modifier.padding(10.dp),
                                )
                            },
                            items = listOf(
                                MenuItem(text = removeRegistrationLabel, danger = true) {
                                    removeAccountId = selected.id
                                },
                            ),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsPill(text = stringResource(R.string.chatgpt_plan_usage))
                    DsPill(text = stringResource(R.string.chatgpt_model_count, state.models.size))
                }
                Text(
                    stringResource(R.string.chatgpt_session_persistence_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )

                if (otherAccounts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chatgpt_saved_accounts),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    otherAccounts.forEach { account ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                        ) {
                            Text(
                                buildString {
                                    append(
                                        account.displayName?.takeIf(String::isNotBlank)
                                            ?: account.email.orEmpty().ifBlank { accountFallbackLabel },
                                    )
                                    append(" · ")
                                    append(accountShortLabel)
                                    append(' ')
                                    append(account.id.takeLast(6))
                                },
                                style = DsType.small13.withReadingWeight(),
                                color = colors.labelSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            DsButton(
                                text = if (account.sharingEnabled) useAccountLabel else reauthorizeLabel,
                                onClick = {
                                    if (account.sharingEnabled) {
                                        viewModel.selectChatGptAccount(account.id) { error -> error?.let(report) }
                                    } else {
                                        viewModel.connectChatGpt(account.id) { error -> error?.let(report) }
                                    }
                                },
                                enabled = accountMutationEnabled,
                                size = DsButtonSize.Small,
                                variant = DsButtonVariant.Ghost,
                            )
                            if (accountMutationEnabled) {
                                DsMenu(
                                    anchor = {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = accountActionsLabel,
                                            tint = colors.labelSecondary,
                                            modifier = Modifier.padding(10.dp),
                                        )
                                    },
                                    items = buildList {
                                        if (account.sharingEnabled) {
                                            add(MenuItem(text = reauthorizeLabel) {
                                                viewModel.connectChatGpt(account.id) { error -> error?.let(report) }
                                            })
                                        }
                                        add(MenuItem(text = removeRegistrationLabel, danger = true) {
                                            removeAccountId = account.id
                                        })
                                    },
                                )
                            }
                        }
                    }
                }

                DsButton(
                    text = stringResource(
                        if (testing) R.string.chatgpt_testing_connection else R.string.chatgpt_test_connection,
                    ),
                    onClick = {
                        testing = true
                        testStatus = null
                        viewModel.testChatGptAccount(selected.id) { result ->
                            testing = false
                            testStatus = result
                        }
                    },
                    enabled = !busy && !testing && selected.sharingEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    size = DsButtonSize.Small,
                )
                Text(
                    stringResource(R.string.chatgpt_test_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
                testStatus?.let {
                    Text(it, style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
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
                        text = reauthorizeLabel,
                        onClick = { viewModel.connectChatGpt(selected.id) { error -> error?.let(report) } },
                        enabled = accountMutationEnabled,
                        modifier = Modifier.weight(1f),
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Outline,
                    )
                    DsButton(
                        text = stringResource(R.string.chatgpt_disconnect),
                        onClick = {
                            viewModel.disconnectChatGptAccount(selected.id) { error -> error?.let(report) }
                        },
                        enabled = accountMutationEnabled,
                        modifier = Modifier.weight(1f),
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
                DsButton(
                    text = stringResource(R.string.chatgpt_add_account),
                    onClick = { viewModel.connectChatGpt { error -> error?.let(report) } },
                    enabled = accountMutationEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }

            if (modelIdentityLocked) {
                Text(
                    stringResource(R.string.chatgpt_account_change_locked),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
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
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
                if (authorizationCanRestart) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        DsButton(
                            text = stringResource(R.string.chatgpt_retry_authorization),
                            onClick = {
                                viewModel.restartChatGptAuthorization(state.pendingAccountId) { error ->
                                    error?.let(report)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Outline,
                        )
                        DsButton(
                            text = stringResource(R.string.chatgpt_cancel_authorization),
                            onClick = viewModel::cancelChatGptAuthorization,
                            modifier = Modifier.weight(1f),
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }
            state.error?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = colors.error) }
        }
    }

    removeAccountId?.let { accountId ->
        DsDialog(
            title = stringResource(R.string.chatgpt_remove_registration_title),
            onDismiss = { removeAccountId = null },
        ) {
            Text(
                stringResource(R.string.chatgpt_remove_registration_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(R.string.chatgpt_cancel),
                    onClick = { removeAccountId = null },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.chatgpt_remove_registration_confirm),
                    onClick = {
                        removeAccountId = null
                        viewModel.removeChatGptAccount(accountId) { warning -> warning?.let(report) }
                    },
                    enabled = accountMutationEnabled,
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Danger,
                )
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
                style = DsType.std14.withReadingWeight(),
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

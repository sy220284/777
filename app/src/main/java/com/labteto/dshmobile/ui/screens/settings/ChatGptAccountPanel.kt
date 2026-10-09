package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.ui.components.FeatherIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESOURCE
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_USAGE_URL
import com.labteto.dshmobile.local.model.chatgpt.shouldRequestChatGptPlanConsent
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsBottomSheet
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
    local: LocalHarnessSettingsState,
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
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var removeAccountId by remember { mutableStateOf<String?>(null) }
    val accountShortLabel = stringResource(R.string.chatgpt_account_short)
    val accountFallbackLabel = stringResource(R.string.chatgpt_account_fallback)
    val useAccountLabel = stringResource(R.string.chatgpt_use_account)
    val reauthorizeLabel = stringResource(R.string.chatgpt_reauthorize)
    val enablePlanLabel = stringResource(R.string.chatgpt_enable_plan_usage)
    val removeRegistrationLabel = stringResource(R.string.chatgpt_remove_registration)
    val accountActionsLabel = stringResource(R.string.chatgpt_account_actions)
    val selected = state.selectedAccount
    val planModels = chatGptAccountModelRows(state, local.modelSelection)
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
                    if (selected == null) {
                        Text(
                            stringResource(R.string.chatgpt_account_hint),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                    }
                }
                DsStatusPill(
                    state = when {
                        selected?.signedIn == true &&
                            selected.sharingEnabled &&
                            state.phase == ChatGptAuthPhase.CONNECTED -> DsStatus.Done
                        state.phase == ChatGptAuthPhase.ERROR -> DsStatus.Failed
                        else -> DsStatus.Neutral
                    },
                    label = when {
                        selected?.signedIn == true &&
                            selected.sharingEnabled &&
                            state.phase == ChatGptAuthPhase.CONNECTED ->
                            stringResource(R.string.chatgpt_connected)
                        selected?.signedIn == true &&
                            !selected.sharingEnabled &&
                            state.phase == ChatGptAuthPhase.CONNECTED ->
                            stringResource(R.string.chatgpt_signed_in_plan_disabled)
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
                    enabled = !busy,
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
                    if (!busy) {
                        DsMenu(
                            anchor = {
                                Icon(
                                    FeatherIcons.MoreVertical,
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
                    if (selected.sharingEnabled) {
                        DsPill(text = stringResource(R.string.chatgpt_plan_usage))
                        DsPill(text = stringResource(R.string.chatgpt_model_count, planModels.size))
                    } else if (selected.signedIn) {
                        DsPill(text = stringResource(R.string.chatgpt_signed_in_plan_disabled))
                    } else {
                        DsPill(text = stringResource(R.string.chatgpt_not_connected))
                    }
                }

                if (!selected.sharingEnabled && selected.signedIn) {
                    Text(
                        stringResource(R.string.chatgpt_plan_disabled_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                    DsButton(
                        text = enablePlanLabel,
                        onClick = {
                            viewModel.connectChatGpt(selected.id, requestPlanConsent = true) { error -> error?.let(report) }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        size = DsButtonSize.Small,
                    )
                }
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
                                text = when {
                                    account.sharingEnabled -> useAccountLabel
                                    account.signedIn -> enablePlanLabel
                                    else -> reauthorizeLabel
                                },
                                onClick = {
                                    when {
                                        account.sharingEnabled ->
                                            viewModel.selectChatGptAccount(account.id) { error -> error?.let(report) }
                                        else -> viewModel.connectChatGpt(
                                            account.id,
                                            requestPlanConsent = shouldRequestChatGptPlanConsent(account),
                                        ) { error -> error?.let(report) }
                                    }
                                },
                                enabled = !busy,
                                size = DsButtonSize.Small,
                                variant = DsButtonVariant.Ghost,
                            )
                            if (!busy) {
                                DsMenu(
                                    anchor = {
                                        Icon(
                                            FeatherIcons.MoreVertical,
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


                // 账户快捷操作：同一排四等宽入口，窄屏和字体放大时允许文字折行。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    ChatGptAccountAction(
                        label = stringResource(R.string.chatgpt_action_view_usage),
                        icon = FeatherIcons.Activity,
                        onClick = { uriHandler.openUri(CHATGPT_USAGE_URL) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    ChatGptAccountAction(
                        label = stringResource(R.string.chatgpt_action_reconnect),
                        icon = FeatherIcons.RefreshCw,
                        onClick = {
                            viewModel.connectChatGpt(
                                selected.id,
                                requestPlanConsent = shouldRequestChatGptPlanConsent(selected),
                            ) { error -> error?.let(report) }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    ChatGptAccountAction(
                        label = stringResource(R.string.chatgpt_action_disconnect),
                        icon = FeatherIcons.X,
                        onClick = {
                            viewModel.disconnectChatGptAccount(selected.id) { error -> error?.let(report) }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        danger = true,
                    )
                    ChatGptAccountAction(
                        label = stringResource(R.string.chatgpt_action_add_account),
                        icon = FeatherIcons.Plus,
                        onClick = { viewModel.connectChatGpt { error -> error?.let(report) } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (selected.signedIn && selected.sharingEnabled) {
                    Text(
                        stringResource(R.string.chatgpt_account_models_title, planModels.size),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    if (!state.connected && planModels.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chatgpt_account_models_cached),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                    }
                    if (planModels.isEmpty()) {
                        Text(
                            stringResource(
                                if (state.connected) R.string.chatgpt_account_models_empty
                                else R.string.chatgpt_account_models_pending,
                            ),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                    }
                    planModels.forEach { model ->
                        Surface(
                            shape = DsShapes.row,
                            color = colors.wallpaperSurface(WallpaperSurfaceLevel.INPUT),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(DsSpacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                                    ) {
                                        Text(
                                            model.displayName,
                                            style = DsType.std14Strong.withReadingWeight(),
                                            color = colors.labelPrimary,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (model.active) {
                                            DsStatusPill(
                                                DsStatus.Done,
                                                stringResource(R.string.local_model_in_use),
                                            )
                                        }
                                    }
                                    if (model.slug != model.displayName) {
                                        Text(
                                            model.slug,
                                            style = DsType.caption11.withReadingWeight(),
                                            color = colors.labelTertiary,
                                        )
                                    }
                                    ModelCapabilityTags(
                                        LocalModelPresets.clientCapabilitiesFor(model.slug, CHATGPT_RESOURCE),
                                    )
                                }
                                if (!model.active && state.connected) {
                                    model.profileId?.let { profileId ->
                                        DsButton(
                                            text = stringResource(R.string.chatgpt_account_use_model),
                                            onClick = { viewModel.selectLocalModel(profileId) },
                                            size = DsButtonSize.Small,
                                            variant = DsButtonVariant.Ghost,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }

    if (showWelcome) {
        DsBottomSheet(
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

/** 账户页专用紧凑操作：沿用 Ds 颜色、圆角、间距和文字规格。 */
@Composable
private fun ChatGptAccountAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val colors = DsTheme.colors
    val labelColor = (if (danger) colors.error else colors.labelPrimary)
        .copy(alpha = if (enabled) 1f else 0.45f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = DsShapes.row,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.INPUT, base = colors.bgModulePlatform),
        contentColor = labelColor,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = DsSpacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = DsType.caption11Strong.withReadingWeight(),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

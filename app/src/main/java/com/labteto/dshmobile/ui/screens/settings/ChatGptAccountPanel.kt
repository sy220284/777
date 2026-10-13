package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.ui.components.FeatherIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.shenyan.designsystem.foundation.LocalSyColors
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESOURCE
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_USAGE_URL
import com.labteto.dshmobile.local.model.chatgpt.shouldRequestChatGptPlanConsent
import com.labteto.dshmobile.ui.shenyan.SyMaterialFrame
import com.labteto.dshmobile.ui.shenyan.SyMaterialTier
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyMenu
import com.shenyan.designsystem.components.SyPill
import com.labteto.dshmobile.ui.shenyan.SyStatus
import com.labteto.dshmobile.ui.shenyan.SyStatusPill
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatGptAccountPanel(
    state: ChatGptUiState,
    local: LocalHarnessSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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

    SyMaterialFrame(
        modifier = Modifier.fillMaxWidth(),
        tier = SyMaterialTier.L3_CARD,
        horizontalPadding = 0.dp,
        verticalPadding = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.chatgpt_account_title),
                        style = DsType.std14Strong.withReadingWeight(),
                        color = colors.text,
                    )
                    if (selected == null) {
                        Text(
                            stringResource(R.string.chatgpt_account_hint),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textSecondary,
                        )
                    }
                }
                SyStatusPill(
                    state = when {
                        selected?.signedIn == true &&
                            selected.sharingEnabled &&
                            state.phase == ChatGptAuthPhase.CONNECTED -> SyStatus.Done
                        state.phase == ChatGptAuthPhase.ERROR -> SyStatus.Failed
                        else -> SyStatus.Neutral
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
                    color = colors.textTertiary,
                )
                SyButton(
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
                    color = colors.text,
                )
                selected.email?.takeIf { it != selected.displayName }?.let {
                    Text(it, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$accountShortLabel ${selected.id.takeLast(6)}",
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!busy) {
                        SyMenu(
                            anchor = {
                                Icon(
                                    FeatherIcons.MoreVertical,
                                    contentDescription = accountActionsLabel,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.padding(10.dp),
                                )
                            },
                            items = listOf(
                                SyMenuItem(text = removeRegistrationLabel, danger = true) {
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
                        SyPill(text = stringResource(R.string.chatgpt_plan_usage))
                        SyPill(text = stringResource(R.string.chatgpt_model_count, planModels.size))
                    } else if (selected.signedIn) {
                        SyPill(text = stringResource(R.string.chatgpt_signed_in_plan_disabled))
                    } else {
                        SyPill(text = stringResource(R.string.chatgpt_not_connected))
                    }
                }

                if (!selected.sharingEnabled && selected.signedIn) {
                    Text(
                        stringResource(R.string.chatgpt_plan_disabled_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                    SyButton(
                        text = enablePlanLabel,
                        onClick = {
                            viewModel.connectChatGpt(selected.id, requestPlanConsent = true) { error -> error?.let(report) }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        size = SyButtonSize.Small,
                    )
                }
                if (otherAccounts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chatgpt_saved_accounts),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textTertiary,
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
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            SyButton(
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
                                // A later account tap replaces the previous catalog-loading request.
                                enabled = !busy || state.phase == ChatGptAuthPhase.LOADING_MODELS,
                                size = SyButtonSize.Small,
                                variant = SyButtonVariant.Ghost,
                            )
                            if (!busy) {
                                SyMenu(
                                    anchor = {
                                        Icon(
                                            FeatherIcons.MoreVertical,
                                            contentDescription = accountActionsLabel,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.padding(10.dp),
                                        )
                                    },
                                    items = buildList {
                                        if (account.sharingEnabled) {
                                            add(SyMenuItem(text = reauthorizeLabel) {
                                                viewModel.connectChatGpt(account.id) { error -> error?.let(report) }
                                            })
                                        }
                                        add(SyMenuItem(text = removeRegistrationLabel, danger = true) {
                                            removeAccountId = account.id
                                        })
                                    },
                                )
                            }
                        }
                    }
                }

                SyButton(
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
                    size = SyButtonSize.Small,
                )
                Text(
                    stringResource(R.string.chatgpt_test_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                )
                testStatus?.let {
                    Text(it, style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
                }


                // Account management is a two-column action grid; status and identity keep their own space.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    maxItemsInEachRow = 2,
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
                        color = colors.text,
                    )
                    if (!state.connected && planModels.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chatgpt_account_models_cached),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textTertiary,
                        )
                    }
                    if (planModels.isEmpty()) {
                        Text(
                            stringResource(
                                if (state.connected) R.string.chatgpt_account_models_empty
                                else R.string.chatgpt_account_models_pending,
                            ),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textTertiary,
                        )
                    }
                    planModels.forEach { model ->
                        Surface(
                            shape = DsShapes.row,
                            color = LocalSyColors.current.materialL1,
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
                                            color = colors.text,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (model.active) {
                                            SyStatusPill(
                                                SyStatus.Done,
                                                stringResource(R.string.local_model_in_use),
                                            )
                                        }
                                    }
                                    if (model.slug != model.displayName) {
                                        Text(
                                            model.slug,
                                            style = DsType.caption11.withReadingWeight(),
                                            color = colors.textTertiary,
                                        )
                                    }
                                    ModelCapabilityTags(
                                        LocalModelPresets.clientCapabilitiesFor(model.slug, CHATGPT_RESOURCE),
                                    )
                                }
                                if (!model.active && state.connected) {
                                    model.profileId?.let { profileId ->
                                        SyButton(
                                            text = stringResource(R.string.chatgpt_account_use_model),
                                            onClick = { viewModel.selectLocalModel(profileId) },
                                            size = SyButtonSize.Small,
                                            variant = SyButtonVariant.Ghost,
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
                    color = colors.textSecondary,
                )
                if (authorizationCanRestart) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        SyButton(
                            text = stringResource(R.string.chatgpt_retry_authorization),
                            onClick = {
                                viewModel.restartChatGptAuthorization(state.pendingAccountId) { error ->
                                    error?.let(report)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Outline,
                        )
                        SyButton(
                            text = stringResource(R.string.chatgpt_cancel_authorization),
                            onClick = viewModel::cancelChatGptAuthorization,
                            modifier = Modifier.weight(1f),
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                    }
                }
            }
            state.error?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = colors.dangerInk) }
        }
    }

    removeAccountId?.let { accountId ->
        SyDialog(
            title = stringResource(R.string.chatgpt_remove_registration_title),
            onDismiss = { removeAccountId = null },
        ) {
            Text(
                stringResource(R.string.chatgpt_remove_registration_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                SyButton(
                    text = stringResource(R.string.chatgpt_cancel),
                    onClick = { removeAccountId = null },
                    modifier = Modifier.weight(1f),
                    variant = SyButtonVariant.Ghost,
                )
                SyButton(
                    text = stringResource(R.string.chatgpt_remove_registration_confirm),
                    onClick = {
                        removeAccountId = null
                        viewModel.removeChatGptAccount(accountId) { warning -> warning?.let(report) }
                    },
                    modifier = Modifier.weight(1f),
                    variant = SyButtonVariant.Danger,
                )
            }
        }
    }

    if (showWelcome) {
        SyResponsiveSheet(
            title = stringResource(R.string.chatgpt_welcome_title),
            onDismiss = {
                welcomePrefs.edit().putBoolean("plan_welcome_seen_v1", true).apply()
                showWelcome = false
            },
        ) {
            Text(
                stringResource(R.string.chatgpt_welcome_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
            SyButton(
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val labelColor = (if (danger) colors.dangerInk else colors.text)
        .copy(alpha = if (enabled) 1f else 0.45f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = DsShapes.row,
        color = LocalSyColors.current.materialL1,
        contentColor = labelColor,
        border = BorderStroke(1.dp, colors.border),
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

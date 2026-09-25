package com.labteto.dshmobile.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import java.util.Locale

/**
 * App settings, grouped into cards.
 *
 * Only the top group is genuinely the app's own; connection, harness facts and data are all about
 * the *link* to a harness. Keeping the read-only notice scoped to the harness group matters —
 * blanket-labelling the whole screen read-only, as it used to, tells users their own preferences
 * cannot be changed when they plainly can.
 */
private enum class SettingsPage {
    ROOT,
    GENERAL,
    MODELS,
    PRICING,
    MEMORY,
    CHAT,
    PERMISSIONS,
    NOTIFICATIONS,
    ADVANCED,
}

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val sessionSort by viewModel.sessionSort.collectAsStateWithLifecycle()
    val projectSettings by viewModel.projectSettings.collectAsStateWithLifecycle()
    val modelServices by viewModel.modelServices.collectAsStateWithLifecycle()
    val localHarness by viewModel.localHarnessState.collectAsStateWithLifecycle()
    val deepSeekPricing by viewModel.deepSeekPricing.collectAsStateWithLifecycle()
    val visionSettings by viewModel.visionSettings.collectAsStateWithLifecycle()
    val deviceCapabilities by viewModel.deviceCapabilities.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showDiagnostic by rememberSaveable { mutableStateOf(false) }
    var showEnvironment by rememberSaveable { mutableStateOf(false) }
    var environmentInfo by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
    }
    LaunchedEffect(connectionState.phase) {
        viewModel.refreshRemoteSettings()
    }
    LaunchedEffect(page) {
        if (page == SettingsPage.MEMORY) viewModel.refreshMemories()
    }
    LaunchedEffect(showEnvironment) {
        environmentInfo = if (showEnvironment) {
            runCatching { viewModel.environmentInfo() }.getOrElse { it.message.orEmpty() }
        } else {
            null
        }
    }

    val title = when (page) {
        SettingsPage.ROOT -> stringResource(R.string.settings_title)
        SettingsPage.GENERAL -> stringResource(R.string.settings_page_general)
        SettingsPage.MODELS -> stringResource(R.string.settings_page_models)
        SettingsPage.PRICING -> stringResource(R.string.settings_page_pricing)
        SettingsPage.MEMORY -> stringResource(R.string.settings_page_memory)
        SettingsPage.CHAT -> stringResource(R.string.settings_page_chat)
        SettingsPage.PERMISSIONS -> stringResource(R.string.settings_page_permissions)
        SettingsPage.NOTIFICATIONS -> stringResource(R.string.settings_page_notifications)
        SettingsPage.ADVANCED -> stringResource(R.string.settings_page_advanced)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xlarge),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = {
                            if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
                        },
                        containerColor = colors.bgLayer1,
                        shadowElevation = 3.dp,
                    )
                    Text(
                        title,
                        style = DsType.large20,
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.width(48.dp))
                }

                when (page) {
                    SettingsPage.ROOT -> {
                        Text(stringResource(R.string.settings_group_experience), style = DsType.std14, color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Cloud,
                                title = stringResource(R.string.settings_page_models),
                                subtitle = stringResource(R.string.settings_models_subtitle),
                                onClick = { page = SettingsPage.MODELS },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_page_pricing),
                                subtitle = stringResource(R.string.settings_pricing_subtitle),
                                onClick = { page = SettingsPage.PRICING },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Memory,
                                title = stringResource(R.string.settings_page_memory),
                                subtitle = stringResource(R.string.settings_memory_subtitle),
                                onClick = { page = SettingsPage.MEMORY },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_page_chat),
                                subtitle = stringResource(R.string.settings_chat_subtitle),
                                onClick = { page = SettingsPage.CHAT },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Language,
                                title = stringResource(R.string.settings_page_general),
                                subtitle = stringResource(R.string.settings_general_subtitle),
                                onClick = { page = SettingsPage.GENERAL },
                            )
                        }

                        Text(stringResource(R.string.settings_group_system), style = DsType.std14, color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.PhoneAndroid,
                                title = stringResource(R.string.settings_page_permissions),
                                subtitle = stringResource(R.string.settings_permissions_subtitle),
                                onClick = { page = SettingsPage.PERMISSIONS },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Notifications,
                                title = stringResource(R.string.settings_page_notifications),
                                subtitle = stringResource(R.string.settings_notifications_subtitle),
                                onClick = { page = SettingsPage.NOTIFICATIONS },
                            )
                        }

                        Text(stringResource(R.string.settings_group_maintenance), style = DsType.std14, color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_page_advanced),
                                subtitle = stringResource(R.string.settings_advanced_subtitle),
                                onClick = { page = SettingsPage.ADVANCED },
                            )
                        }
                    }

                    SettingsPage.GENERAL -> {
                        SettingsCard(stringResource(R.string.settings_general), Icons.Outlined.Language) {
                            LanguageRow(settings) { tag -> viewModel.set { it.copy(localeOverride = tag) } }
                            AppearanceRow(settings) { mode -> viewModel.set { it.copy(themePreference = mode) } }
                            BackgroundRow(
                                path = settings.backgroundImagePath,
                                adaptiveContrast = settings.backgroundAdaptiveContrast,
                                onAdaptiveContrastChange = { enabled ->
                                    viewModel.set { it.copy(backgroundAdaptiveContrast = enabled) }
                                },
                                onPick = { uri -> viewModel.setBackgroundImage(uri) },
                                onClear = { viewModel.clearBackgroundImage() },
                            )
                        }
                        SettingsCard(stringResource(R.string.chatlist_title), Icons.Outlined.History) {
                            ToggleRow(
                                stringResource(R.string.chatlist_sort_updated),
                                sessionSort == "updated",
                                stringResource(R.string.chatlist_sort_manual),
                            ) { viewModel.setSessionSortByRecency(sessionSort != "updated") }
                        }
                    }

                    SettingsPage.MODELS -> {
                        LocalModelSettingsCard(localHarness, viewModel, toast.second)
                        ModelServicesCard(modelServices, viewModel)
                        LocalVisionSettingsCard(visionSettings, viewModel, toast.second)
                    }

                    SettingsPage.PRICING -> {
                        DeepSeekPricingCard(deepSeekPricing, viewModel)
                    }

                    SettingsPage.MEMORY -> {
                        LocalMemorySettingsCard(localHarness, viewModel, toast.second)
                        MemoryManagementCard(memories, viewModel, toast.second)
                    }

                    SettingsPage.CHAT -> {
                        SettingsCard(stringResource(R.string.settings_chat_style_guard), Icons.Outlined.Tune) {
                            ToggleRow(
                                stringResource(R.string.settings_chat_style_guard),
                                localHarness.chatStyleGuardEnabled,
                                stringResource(R.string.settings_chat_style_guard_hint),
                            ) {
                                viewModel.configureChatStyleGuard(!localHarness.chatStyleGuardEnabled)
                            }
                            Text(
                                stringResource(R.string.settings_chat_guard_hits_title),
                                style = DsType.small13,
                                color = colors.labelSecondary,
                            )
                            if (localHarness.styleGuardHits.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_chat_guard_hits_empty),
                                    style = DsType.caption11,
                                    color = colors.labelTertiary,
                                )
                            } else {
                                localHarness.styleGuardHits.takeLast(8).asReversed().forEach { hit ->
                                    Text(
                                        "• " + hit,
                                        style = DsType.caption11,
                                        color = colors.labelSecondary,
                                    )
                                }
                                DsButton(
                                    text = stringResource(R.string.settings_chat_guard_hits_clear),
                                    onClick = viewModel::clearChatStyleGuardHits,
                                    variant = DsButtonVariant.Ghost,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    SettingsPage.PERMISSIONS -> {
                        SettingsCard(stringResource(R.string.settings_connection), Icons.Outlined.Link) {
                            ConnectionSection(connectionState, onDisconnect = { showDisconnectDialog = true })
                            ToggleRow(
                                stringResource(R.string.settings_background),
                                settings.keepConnectedInBackground,
                                stringResource(R.string.settings_background_hint),
                            ) { viewModel.set { it.copy(keepConnectedInBackground = !it.keepConnectedInBackground) } }
                        }
                        DeviceCapabilitiesCard(deviceCapabilities, viewModel)
                    }

                    SettingsPage.NOTIFICATIONS -> {
                        SettingsCard(stringResource(R.string.settings_notifications), Icons.Outlined.Notifications) {
                            ToggleRow(
                                stringResource(R.string.settings_notifications_turn),
                                settings.notifyTurnComplete,
                                stringResource(R.string.settings_notifications_turn_hint),
                            ) { viewModel.set { it.copy(notifyTurnComplete = !it.notifyTurnComplete) } }
                            ToggleRow(
                                stringResource(R.string.settings_notifications_goal),
                                settings.notifyGoal,
                                stringResource(R.string.settings_notifications_goal_hint),
                            ) { viewModel.set { it.copy(notifyGoal = !it.notifyGoal) } }
                            ToggleRow(
                                stringResource(R.string.settings_notifications_action),
                                settings.notifyNeedsAction,
                                stringResource(R.string.settings_notifications_action_hint),
                            ) { viewModel.set { it.copy(notifyNeedsAction = !it.notifyNeedsAction) } }
                            ToggleRow(
                                stringResource(R.string.settings_notifications_local_jobs),
                                settings.notifyLocalJobs,
                                stringResource(R.string.settings_notifications_local_jobs_hint),
                            ) { viewModel.set { it.copy(notifyLocalJobs = !it.notifyLocalJobs) } }
                        }
                    }

                    SettingsPage.ADVANCED -> {
                        LocalAgentSettingsCard(localHarness, viewModel, toast.second)
                        ProjectSettingsCard(projectSettings, viewModel, toast.second)
                        SettingsCard(stringResource(R.string.settings_runtime_diagnostics), Icons.Outlined.Info) {
                            DsButton(
                                text = stringResource(R.string.settings_network_diagnostic),
                                onClick = { showDiagnostic = true },
                                variant = DsButtonVariant.Outline,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DsButton(
                                text = stringResource(R.string.settings_environment_capabilities),
                                onClick = { showEnvironment = true },
                                variant = DsButtonVariant.Ghost,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                }

                Spacer(Modifier.height(DsSpacing.xlarge))
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showDiagnostic) {
        NetworkDiagnosticDialog(
            onDismiss = { showDiagnostic = false },
            diagnose = viewModel::diagnoseNetwork,
        )
    }

    if (showEnvironment) {
        EnvironmentInfoDialog(
            text = environmentInfo ?: stringResource(R.string.common_loading),
            onDismiss = { showEnvironment = false },
        )
    }

    if (showDisconnectDialog) {
        DsDialog(
            title = stringResource(R.string.settings_connection_disconnect_confirm),
            onDismiss = { showDisconnectDialog = false },
        ) {
            Text(
                stringResource(R.string.settings_connection_disconnect_message),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DsSpacing.medium),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(
                    text = stringResource(R.string.settings_connection_disconnect),
                    onClick = {
                        viewModel.disconnect()
                        showDisconnectDialog = false
                        onClose()
                    },
                    variant = DsButtonVariant.Danger,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showDisconnectDialog = false },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** One settings group as a raised card, so groups read as blocks rather than a running list. */
@Composable
internal fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.padding(
                start = DsSpacing.small,
                end = DsSpacing.small,
                bottom = DsSpacing.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(icon, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(18.dp))
            Text(title, style = DsType.std14, color = colors.labelTertiary)
        }
        DsGroupCard {
            content()
        }
    }
}

/**
 * The host's composed plugins, as one row that opens the list.
 *
 * A stock web composition mounts around forty of them. Inlined into a page that already scrolls,
 * that buries everything below it and gives the list no room of its own; the count is the part
 * worth seeing without asking, so the card carries that and the rest lives in [PluginsSheet].
 *
 * Read-only, because that is all the harness offers a client: `pluginInventory/list` has no
 * counterpart that changes anything, and the `settings.*` calls behind the web UI's configurable
 * plugin cards are loopback-pinned and answer 403 over the network. Enabling or disabling one means
 * editing `cordis.patch.yml` on the harness computer.
 */
@Composable
private fun PluginsCard(inventory: PluginInventorySnapshot, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    SettingsCard(stringResource(R.string.settings_plugins), Icons.Outlined.Extension) {
        if (inventory.entries.isEmpty()) {
            Text(
                stringResource(R.string.plugins_empty),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            return@SettingsCard
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable(onClick = onOpen)
                .padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_plugins_inventory),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                inventory.entries.size.toString(),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(DsSpacing.xsmall))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The plugin list itself.
 *
 * A sheet rather than an expanding block: forty rows need their own scroll surface, and nesting one
 * inside the settings page's scroll means the list and the page fight over the same drag. The
 * filter sits above the scroll so it stays reachable however far down the list you are.
 *
 * Rows show the shortened module name, since the scope and `dsh-host-`/`dsh-client-` prefixes are
 * the same on nearly every row and push the part that differs off the end of a phone screen.
 */
@Composable
private fun PluginsSheet(inventory: PluginInventorySnapshot, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    var filter by remember { mutableStateOf("") }
    val matching = remember(inventory, filter) {
        val q = filter.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            inventory.entries
        } else {
            inventory.entries.filter {
                it.moduleName.lowercase(Locale.ROOT).contains(q) ||
                    it.entryId.lowercase(Locale.ROOT).contains(q)
            }
        }
    }
    DsBottomSheet(
        title = stringResource(R.string.settings_plugins),
        subtitle = inventory.entries.size.toString(),
        onDismiss = onDismiss,
    ) {
        TextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.plugins_search_hint), style = DsType.std14) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.bgLayer2,
                unfocusedContainerColor = colors.bgLayer2,
                focusedIndicatorColor = colors.accent,
                unfocusedIndicatorColor = colors.borderL2,
                cursorColor = colors.accent,
            ),
        )
        if (matching.isEmpty()) {
            Text(
                stringResource(R.string.plugins_empty),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            return@DsBottomSheet
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(matching, key = { it.entryId }) { entry -> PluginRow(entry) }
        }
    }
}

@Composable
private fun PluginRow(entry: PluginInventoryEntry) {
    val colors = DsTheme.colors
    var expanded by remember(entry.entryId) { mutableStateOf(false) }
    DisclosureRow(
        title = moduleShortName(entry.moduleName),
        summary = stringResource(
            if (entry.enabled) R.string.plugins_enabled else R.string.plugins_disabled,
        ),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(start = DsSpacing.xlarge, bottom = DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(entry.entryId, style = DsType.caption11, color = colors.labelCaption)
            Text(entry.moduleName, style = DsType.caption11, color = colors.labelCaption)
            // The mount phase only means anything for a plugin the composition asked for.
            if (entry.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(
                        when (entry.fiberPhase) {
                            PluginFiberPhase.ACTIVE -> StateDotState.Done
                            PluginFiberPhase.LOADING, PluginFiberPhase.UNLOADING -> StateDotState.Running
                            PluginFiberPhase.FAILED -> StateDotState.Error
                            PluginFiberPhase.PENDING, null -> StateDotState.Idle
                        },
                    )
                    Spacer(Modifier.width(DsSpacing.xsmall))
                    Text(
                        stringResource(pluginPhaseLabel(entry.fiberPhase)),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                }
            }
        }
    }
}

@StringRes
private fun pluginPhaseLabel(phase: PluginFiberPhase?): Int = when (phase) {
    PluginFiberPhase.PENDING -> R.string.plugins_phase_pending
    PluginFiberPhase.LOADING -> R.string.plugins_phase_loading
    PluginFiberPhase.ACTIVE -> R.string.plugins_phase_active
    PluginFiberPhase.FAILED -> R.string.plugins_phase_failed
    PluginFiberPhase.UNLOADING -> R.string.plugins_phase_unloading
    null -> R.string.plugins_phase_unmounted
}

/**
 * `@deepseek-ai/dsh-client-ui-plan` → `ui-plan`.
 *
 * Ported from the harness's own `moduleShortName`, prefix for prefix, so the two lists name the
 * same plugin the same way.
 */
private fun moduleShortName(moduleName: String): String {
    val prefixes = listOf("cordis:", "cordis-plugin-", "dsh-host-", "dsh-client-", "dsh-")
    var name = moduleName.substringAfterLast('/')
    for (prefix in prefixes) name = name.removePrefix(prefix)
    return name.ifBlank { moduleName }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsType.std14, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun ConnectionSection(connectionState: ConnectionUiState, onDisconnect: () -> Unit) {
    val colors = DsTheme.colors
    val isConnected = connectionState.phase == ConnectionPhase.CONNECTED ||
        connectionState.phase == ConnectionPhase.RECONNECTING

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_connection_status),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> StateDotState.Done
                    ConnectionPhase.RECONNECTING, ConnectionPhase.CONNECTING -> StateDotState.Running
                    else -> StateDotState.Idle
                },
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> stringResource(R.string.common_connected)
                    ConnectionPhase.RECONNECTING -> stringResource(R.string.common_reconnecting)
                    ConnectionPhase.CONNECTING -> stringResource(R.string.common_loading)
                    else -> stringResource(R.string.common_offline)
                },
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }

    if (isConnected && connectionState.host != null) {
        LabelledValue(
            stringResource(R.string.settings_connection_host),
            connectionState.host.authority,
        )
        DsButton(
            text = stringResource(R.string.settings_connection_disconnect),
            onClick = onDisconnect,
            variant = DsButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LanguageRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    // A compact dropdown keeps the language choice secondary to the settings users change often.
    val systemLanguage = LocalConfiguration.current.locales[0].language
    val effectiveTag = when {
        settings.localeOverride?.startsWith("zh", ignoreCase = true) == true -> "zh-CN"
        settings.localeOverride == "en" -> "en"
        systemLanguage.equals("zh", ignoreCase = true) -> "zh-CN"
        else -> "en"
    }
    val current = LanguageOptions.first { it.tag == effectiveTag }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_language),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        DsMenu(
            anchor = {
                Row(
                    modifier = Modifier
                        .clip(DsShapes.pillFull)
                        .background(colors.hoverSolid)
                        .border(1.dp, colors.borderL2, DsShapes.pillFull)
                        .padding(horizontal = DsSpacing.compact, vertical = DsSpacing.tiny),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(
                        current.label,
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            },
            items = LanguageOptions.map { option ->
                MenuItem(
                    text = option.label,
                    icon = Icons.Filled.Check.takeIf { option == current },
                ) { onSelect(option.tag) }
            },
        )
    }
}

@Composable
private fun AppearanceRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_appearance),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                AppearanceChip(
                    stringResource(R.string.settings_appearance_light),
                    settings.themePreference == "light",
                ) { onSelect("light") }
                AppearanceChip(
                    stringResource(R.string.settings_appearance_dark),
                    settings.themePreference == "dark",
                ) { onSelect("dark") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                AppearanceChip(
                    stringResource(R.string.settings_appearance_matte_black),
                    settings.themePreference == "matte_black",
                ) { onSelect("matte_black") }
                AppearanceChip(
                    stringResource(R.string.settings_appearance_system),
                    settings.themePreference == "system",
                ) { onSelect("system") }
            }
        }
    }
}

@Composable
private fun AppearanceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .clip(DsShapes.cube)
            .background(if (selected) colors.accentTertiary else colors.bgModulePlatform)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = DsSpacing.small),
    ) {
        Text(
            label,
            style = DsType.small13,
            color = if (selected) colors.accent else colors.labelSecondary,
        )
    }
}

/**
 * Pick or drop the app-wide background image.
 *
 * The picked bytes are copied into app storage rather than the URI kept, which is why the document
 * picker is enough here: it is also the picker this app already uses for attachments, and one
 * picker is one thing to keep working.
 */
@Composable
private fun BackgroundRow(
    path: String?,
    adaptiveContrast: Boolean,
    onAdaptiveContrastChange: (Boolean) -> Unit,
    onPick: (Uri) -> Unit,
    onClear: () -> Unit,
) {
    val colors = DsTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_background_image),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Text(
            stringResource(
                if (path == null) {
                    R.string.settings_background_image_none
                } else {
                    R.string.settings_background_image_active
                },
            ),
            style = DsType.small13,
            color = colors.labelTertiary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            AppearanceChip(stringResource(R.string.settings_background_image_choose), false) {
                picker.launch(arrayOf("image/*"))
            }
            if (path != null) {
                AppearanceChip(stringResource(R.string.settings_background_image_clear), false) {
                    onClear()
                }
            }
        }
        if (path != null) {
            Spacer(Modifier.height(DsSpacing.small))
            ToggleRow(
                stringResource(R.string.settings_background_adaptive_contrast),
                adaptiveContrast,
                stringResource(R.string.settings_background_adaptive_contrast_hint),
            ) { onAdaptiveContrastChange(!adaptiveContrast) }
        }
    }
}

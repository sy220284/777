package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric

import com.labteto.dshmobile.ui.shenyan.SyButtonSize

import android.Manifest
import android.os.Build
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.shenyan.SyToastHost
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.labteto.dshmobile.ui.shenyan.SyHierarchyPage
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.shenyan.SyUserBubble
import com.labteto.dshmobile.ui.shenyan.rememberSyToast
import com.labteto.dshmobile.ui.theme.AccentPalettes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App settings with flat sections and secondary configuration destinations.
 *
 * Only the top group is genuinely the app's own; connection, harness facts and data are all about
 * the *link* to a harness. Keeping the read-only notice scoped to the harness group matters —
 * blanket-labelling the whole screen read-only, as it used to, tells users their own preferences
 * cannot be changed when they plainly can.
 */
enum class SettingsDestination {
    ROOT,
    SESSION,
    APPEARANCE,
    CHAT,
    MODELS,
    MODEL_PERFORMANCE,
    ACCOUNT,
    MODEL_USAGE,
    MINIMAX_QUOTA,
    PRICING,
    USAGE,
    USAGE_LOG,
    USAGE_DETAIL,
    MEMORY,
    MEMORY_SETTINGS,
    MEMORY_MANAGEMENT,
    PERMISSIONS,
    NOTIFICATIONS,
    ADVANCED,
    SESSION_STORAGE,
    DIAGNOSTICS,
}

internal fun encodeUsageDetailSelection(selection: UsageDetailSelection?): List<String> = when (selection) {
    null -> emptyList()
    is UsageDetailSelection.Request -> listOf("request", selection.requestId)
    is UsageDetailSelection.Group -> listOf(
        "group",
        selection.kind.name,
        selection.key,
        selection.title,
    )
}

internal fun decodeUsageDetailSelection(saved: List<String>): UsageDetailSelection? = when (saved.firstOrNull()) {
    "request" -> saved.getOrNull(1)
        ?.takeIf(String::isNotBlank)
        ?.let(UsageDetailSelection::Request)
    "group" -> {
        if (saved.size < 4) null else {
            runCatching { com.labteto.dshmobile.local.TokenUsageGroupKind.valueOf(saved[1]) }
                .getOrNull()
                ?.let { kind ->
                    UsageDetailSelection.Group(
                        kind = kind,
                        key = saved[2],
                        title = saved[3],
                    )
                }
        }
    }
    else -> null
}

private val usageDetailSelectionStateSaver =
    androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.MutableState<UsageDetailSelection?>, String>(
        save = { state -> encodeUsageDetailSelection(state.value) },
        restore = { saved -> mutableStateOf(decodeUsageDetailSelection(saved)) },
    )

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    initialDestination: SettingsDestination = SettingsDestination.ROOT,
    entryRequest: Int = 0,
    memorySourceSessionId: String? = null,
    memorySourceMessageId: String? = null,
    onCheckUpdate: () -> Unit = {},
    updateStatus: String? = null,
    handleRootSystemBack: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val localHarness by viewModel.localHarnessState.collectAsStateWithLifecycle()
    val approvalMode by viewModel.approvalMode.collectAsStateWithLifecycle()
    val deepSeekPricing by viewModel.deepSeekPricing.collectAsStateWithLifecycle()
    val usageAnalytics by viewModel.usageAnalytics.collectAsStateWithLifecycle()
    val deviceCapabilities by viewModel.deviceCapabilities.collectAsStateWithLifecycle()
    // Re-probe after returning from Android settings; a stale permission badge is misleading.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshDeviceCapabilities() }
    val memoryManagement by viewModel.memoryManagement.collectAsStateWithLifecycle()
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val toast = rememberSyToast()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshDeviceCapabilities() }
    val requestPostingPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.openAppNotificationSettings()
        }
    }
    val scope = rememberCoroutineScope()
    val exportFailed = stringResource(R.string.settings_export_diagnostics_failed)
    val exportSuccess = stringResource(R.string.settings_export_diagnostics_success)
    val exportPartial = stringResource(R.string.settings_export_diagnostics_partial)
    val storageExported = stringResource(R.string.settings_local_session_storage_exported)
    val storageExportFailed = stringResource(R.string.settings_local_session_storage_export_failed)
    val storageCompacted = stringResource(R.string.settings_local_session_storage_compacted)
    val storageCompactFailed = stringResource(R.string.settings_local_session_storage_compact_failed)
    val diagnosticExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try {
                val report = viewModel.diagnosticReport()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(report.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    } ?: error("Cannot open diagnostic export destination")
                }
                val integrity = report.lineSequence()
                    .firstOrNull { it.startsWith("日志完整性：") }
                    ?.removePrefix("日志完整性：")
                toast.second(if (integrity == "正常") exportSuccess else exportPartial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "export_diagnostic_report", error)
                toast.second(exportFailed)
            }
        }
    }
    var pageStack by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(
        listOf(initialDestination), entryRequest,
    )
    val page = pageStack.last()
    fun openPage(destination: SettingsDestination) {
        pageStack = settingsNavigationPush(pageStack, destination)
    }
    var showAllMemories by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(
        false, entryRequest, memorySourceSessionId, memorySourceMessageId,
    )
    DisposableEffect(viewModel) {
        onDispose { viewModel.focusMemorySourceMessage(null, null) }
    }
    var usageDetailSelection by rememberSaveable(saver = usageDetailSelectionStateSaver) {
        mutableStateOf<UsageDetailSelection?>(null)
    }
    var usageDetailBackSelection by rememberSaveable(saver = usageDetailSelectionStateSaver) {
        mutableStateOf<UsageDetailSelection?>(null)
    }
    var selectedUsageModel by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiagnostic by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(false, entryRequest)
    var showEnvironment by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(false, entryRequest)
    var environmentInfo by remember { mutableStateOf<String?>(null) }
    var customChatFilterDraft by rememberSaveable { mutableStateOf("") }
    var showChatPreferenceDiagnostics by rememberSaveable { mutableStateOf(false) }
    var localSessionStorageStatus by remember { mutableStateOf<LocalSessionStorageStatus?>(null) }
    var localSessionStorageBusy by rememberSaveable { mutableStateOf(false) }
    val sessionStorageExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) scope.launch {
            localSessionStorageBusy = true
            try {
                viewModel.exportLocalSessionStorage(uri)
                localSessionStorageStatus = viewModel.localSessionStorageStatus()
                toast.second(storageExported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "export_session_archive", error)
                toast.second(storageExportFailed)
            } finally {
                localSessionStorageBusy = false
            }
        }
    }

    val sessionStorageImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            localSessionStorageBusy = true
            try {
                val sessions = viewModel.importLocalSessionStorage(uri)
                localSessionStorageStatus = viewModel.localSessionStorageStatus()
                toast.second("已验证并安排恢复 $sessions 个会话，重启应用后生效")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "stage_session_archive_restore", error)
                toast.second(error.message ?: "会话备份导入失败")
            } finally {
                localSessionStorageBusy = false
            }
        }
    }

    fun navigateBack() {
        if (page == SettingsDestination.USAGE_DETAIL && usageDetailBackSelection != null) {
            usageDetailSelection = usageDetailBackSelection
            usageDetailBackSelection = null
        } else {
            settingsNavigationPop(pageStack)?.let { pageStack = it } ?: onClose()
        }
    }

    BackHandler(enabled = handleRootSystemBack || page != SettingsDestination.ROOT) { navigateBack() }
    LaunchedEffect(page) {
        if (page == SettingsDestination.PERMISSIONS || page == SettingsDestination.NOTIFICATIONS) {
            viewModel.refreshDeviceCapabilities()
        }
    }
    LaunchedEffect(page, memorySourceSessionId, memorySourceMessageId, showAllMemories) {
        if (page == SettingsDestination.MEMORY || page == SettingsDestination.MEMORY_MANAGEMENT) {
            val focus = page == SettingsDestination.MEMORY_MANAGEMENT && !showAllMemories
            viewModel.focusMemorySourceMessage(
                memorySourceSessionId.takeIf { focus },
                memorySourceMessageId.takeIf { focus },
            )
        }
        if (page == SettingsDestination.SESSION_STORAGE) {
            localSessionStorageStatus = runCatching { viewModel.localSessionStorageStatus() }.getOrNull()
        }
    }
    LaunchedEffect(showEnvironment) {
        environmentInfo = if (showEnvironment) {
            runCatching { viewModel.environmentInfo() }.getOrElse { it.message.orEmpty() }
        } else {
            null
        }
    }

    val title = when (page) {
        SettingsDestination.ROOT -> stringResource(R.string.settings_title)
        SettingsDestination.SESSION -> stringResource(R.string.settings_page_session)
        SettingsDestination.APPEARANCE -> stringResource(R.string.settings_page_appearance)
        SettingsDestination.CHAT -> stringResource(R.string.settings_page_chat)
        SettingsDestination.ACCOUNT -> stringResource(R.string.chatgpt_account_title)
        SettingsDestination.MODELS -> stringResource(R.string.settings_page_models)
        SettingsDestination.MODEL_PERFORMANCE -> stringResource(R.string.settings_model_performance_title)
        SettingsDestination.MODEL_USAGE -> stringResource(R.string.settings_page_model_usage)
        SettingsDestination.MINIMAX_QUOTA -> stringResource(R.string.minimax_quota_title)
        SettingsDestination.PRICING -> stringResource(R.string.settings_page_pricing)
        SettingsDestination.USAGE -> stringResource(R.string.usage_calculation_title)
        SettingsDestination.USAGE_LOG -> stringResource(R.string.usage_log_title)
        SettingsDestination.USAGE_DETAIL -> when (usageDetailSelection) {
            is UsageDetailSelection.Request -> stringResource(R.string.usage_request_detail)
            else -> stringResource(R.string.usage_group_detail)
        }
        SettingsDestination.MEMORY -> stringResource(R.string.settings_page_memory)
        SettingsDestination.MEMORY_SETTINGS -> stringResource(R.string.advanced_memory_settings)
        SettingsDestination.MEMORY_MANAGEMENT -> stringResource(R.string.advanced_manage_memory)
        SettingsDestination.PERMISSIONS -> stringResource(R.string.settings_page_permissions)
        SettingsDestination.NOTIFICATIONS -> stringResource(R.string.settings_page_notifications)
        SettingsDestination.ADVANCED -> stringResource(R.string.settings_page_advanced)
        SettingsDestination.SESSION_STORAGE -> stringResource(R.string.settings_local_session_storage)
        SettingsDestination.DIAGNOSTICS -> stringResource(R.string.settings_runtime_diagnostics)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                SyTopBar(
                    title = title,
                    onBack = ::navigateBack,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = page == SettingsDestination.ROOT,
                    plainBackButton = page == SettingsDestination.ROOT,
                    modifier = Modifier.fillMaxWidth(),
                    actionIcon = FeatherIcons.Clock.takeIf { page == SettingsDestination.USAGE },
                    actionContentDescription = stringResource(R.string.usage_log_open)
                        .takeIf { page == SettingsDestination.USAGE },
                    onAction = if (page == SettingsDestination.USAGE) {
                        { openPage(SettingsDestination.USAGE_LOG) }
                    } else {
                        null
                    },
                )

                SyHierarchyPage(
                    page = pageStack,
                    pageKey = { it.last().name },
                    depth = { it.size },
                    modifier = Modifier.weight(1f),
                ) { shownStack ->
                val shownPage = shownStack.last()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapMd),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                ) {
                when (shownPage) {
                    SettingsDestination.ROOT -> {
                        AppSettingsMenuGroup(stringResource(R.string.settings_group_appearance_experience)) {
                            AppSettingsRow(icon = SyEntryIcons.Appearance,
                                title = stringResource(R.string.settings_page_appearance),
                                onClick = { openPage(SettingsDestination.APPEARANCE) }, compact = true)
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = SyEntryIcons.Notifications,
                                title = stringResource(R.string.settings_page_notifications),
                                onClick = { openPage(SettingsDestination.NOTIFICATIONS) },
                                compact = true,
                            )
                        }
                        AppSettingsMenuGroup(stringResource(R.string.settings_group_models_account)) {
                            AppSettingsRow(
                                icon = SyEntryIcons.Models,
                                title = stringResource(R.string.settings_page_models),
                                onClick = { openPage(SettingsDestination.MODELS) },
                                compact = true,
                            )
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = SyEntryIcons.Account,
                                title = stringResource(R.string.chatgpt_account_title),
                                onClick = { openPage(SettingsDestination.ACCOUNT) },
                                compact = true,
                            )
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = SyEntryIcons.Performance,
                                title = stringResource(R.string.settings_model_performance_title),
                                reservedEntryHeight = 72.dp,
                                onClick = { openPage(SettingsDestination.MODEL_PERFORMANCE) },
                                compact = true,
                            )
                        }
                        AppSettingsMenuGroup(stringResource(R.string.settings_group_memory_data)) {
                            AppSettingsRow(icon = SyEntryIcons.Memory,
                                title = stringResource(R.string.settings_page_memory),
                                onClick = { openPage(SettingsDestination.MEMORY) }, compact = true)
                            AppSettingsDivider()
                            AppSettingsRow(icon = SyEntryIcons.SessionStorage,
                                title = stringResource(R.string.fluid_settings_session_storage),
                                onClick = { openPage(SettingsDestination.SESSION_STORAGE) }, compact = true)
                        }
                        AppSettingsMenuGroup(stringResource(R.string.settings_group_system_maintenance)) {
                            AppSettingsRow(
                                icon = SyEntryIcons.Permissions,
                                title = stringResource(R.string.settings_page_permissions),
                                value = stringResource(when {
                                    deviceCapabilities.loading -> R.string.common_loading
                                    deviceCapabilities.error != null -> R.string.settings_permissions_check_failed
                                    deviceCapabilities.accessibility && deviceCapabilities.notifications &&
                                        deviceCapabilities.postingNotifications == true -> R.string.settings_permissions_ready
                                    else -> R.string.settings_permissions_needs_setup
                                }),
                                onClick = { openPage(SettingsDestination.PERMISSIONS) },
                                compact = true,
                            )
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = SyEntryIcons.Advanced,
                                title = stringResource(R.string.settings_page_advanced),
                                onClick = { openPage(SettingsDestination.ADVANCED) },
                                compact = true,
                            )
                            AppSettingsDivider()
                            UpdateCheckSettingsRow(
                                updateStatus = updateStatus,
                                onCheckUpdate = onCheckUpdate,
                            )
                        }
                        Text(
                            "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textTertiary,
                            modifier = Modifier.fillMaxWidth()
                                .padding(top = SyMetric.gapMd)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                    }

                    SettingsDestination.SESSION -> {
                        AppSettingsSection {
                            AppSettingsRow(
                                icon = SyEntryIcons.ChatStyle,
                                title = stringResource(R.string.settings_chat_style_guard),
                                reservedEntryHeight = 88.dp,
                                value = stringResource(
                                    if (localHarness.chatStyleGuardEnabled) {
                                        R.string.common_enabled
                                    } else {
                                        R.string.common_disabled
                                    },
                                ),
                                onClick = { openPage(SettingsDestination.CHAT) },
                            )
                        }
                    }

                    SettingsDestination.APPEARANCE -> {
                        // Keep common appearance compact; advanced appearance expands in place.
                        SettingsSeamlessGroup(stringResource(R.string.settings_appearance)) {
                            AppearanceRow(settings) { mode ->
                                viewModel.set { it.copy(themePreference = mode) }
                            }
                        }
                        SettingsSeamlessGroup(stringResource(R.string.settings_accent_theme)) {
                            AccentThemeRow(settings) { key ->
                                viewModel.set { it.copy(accentTheme = key) }
                            }
                        }
                        SettingsSeamlessGroup(stringResource(R.string.settings_reading_preferences)) {
                            ReadingPreferencesRow(
                                settings,
                                onTextScaleChange = { value ->
                                    viewModel.set { it.copy(textScale = value.coerceIn(com.labteto.dshmobile.connection.AppDisplayLimits.MIN_READING_SCALE, com.labteto.dshmobile.connection.AppDisplayLimits.MAX_READING_SCALE)) }
                                },
                                onTextWeightChange = { value ->
                                    viewModel.set { it.copy(textWeightAdjustment = value.coerceIn(0, 2)) }
                                },
                            )
                            Text(
                                stringResource(R.string.settings_appearance_preview),
                                style = DsType.std14Strong.withReadingWeight(),
                                color = colors.text,
                            )
                            AppearanceReadingPreview()
                        }
                        SettingsSeamlessGroup(stringResource(R.string.settings_background_image)) {
                            BackgroundRow(
                                settings.backgroundImagePath,
                                onPick = { uri -> viewModel.setBackgroundImage(uri) { message -> toast.second(message) } },
                                onClear = viewModel::clearBackgroundImage,
                            )
                        }
                        var advancedExpanded by rememberSaveable { mutableStateOf(false) }
                        SettingsSeamlessGroup(stringResource(R.string.settings_advanced_appearance)) {
                            ToggleRow(
                                stringResource(R.string.settings_advanced_expand),
                                advancedExpanded,
                            ) { advancedExpanded = !advancedExpanded }
                            if (advancedExpanded) {
                                AppearanceAdvancedControls(
                                    settings = settings,
                                    onAmbientChange = { value ->
                                        viewModel.set { it.copy(ambientGlow = value.coerceIn(0f, 1f)) }
                                    },
                                    onTransparencyChange = { value ->
                                        viewModel.set { it.copy(wallpaperSurfaceTransparency = value.coerceIn(0f, 1f)) }
                                    },
                                    onMotionChange = { value ->
                                        viewModel.set { it.copy(motionDurationScale = value.coerceIn(.7f, 1.3f)) }
                                    },
                                    onEmphasisChange = { value ->
                                        viewModel.set { it.copy(componentEmphasis = value.coerceIn(0f, 1f)) }
                                    },
                                    onAdaptiveContrastChange = { enabled ->
                                        viewModel.set { it.copy(backgroundAdaptiveContrast = enabled) }
                                    },
                                    onReset = {
                                        viewModel.set {
                                            it.copy(
                                                ambientGlow = .5f,
                                                wallpaperSurfaceTransparency = .5f,
                                                motionDurationScale = 1f,
                                                componentEmphasis = .5f,
                                                backgroundAdaptiveContrast = true,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    SettingsDestination.CHAT -> {
                        val customFilters = localHarness.chatStyleGuardCustomPhrases
                        val personaFilters = localHarness.chatPersona.bannedPhrases
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct()
                        val candidate = customChatFilterDraft.trim()
                        val canAddFilter =
                            candidate.isNotEmpty() &&
                                candidate.length <= MAX_CUSTOM_CHAT_FILTER_CHARS &&
                                candidate !in customFilters &&
                                customFilters.size < MAX_CUSTOM_CHAT_FILTERS

                        SettingsCard(stringResource(R.string.settings_page_chat)) {
                            ToggleRow(
                                stringResource(R.string.settings_chat_style_guard),
                                localHarness.chatStyleGuardEnabled,
                                            ) {
                                viewModel.configureChatStyleGuard(!localHarness.chatStyleGuardEnabled)
                            }

                            if (!localHarness.chatStyleGuardEnabled) {
                                Text(
                                    stringResource(R.string.settings_chat_filter_disabled_notice),
                                    style = DsType.small13.withReadingWeight(),
                                    color = colors.textSecondary,
                                )
                            }

                            Text(
                                stringResource(R.string.settings_chat_custom_filters_title, customFilters.size),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.text,
                            )
                            Text(
                                stringResource(
                                    R.string.settings_chat_filter_limits,
                                    MAX_CUSTOM_CHAT_FILTERS,
                                    MAX_CUSTOM_CHAT_FILTER_CHARS,
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.textTertiary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            ) {
                                SyTextField(
                                    value = customChatFilterDraft,
                                    onValueChange = {
                                        customChatFilterDraft = it
                                            .replace("\n", " ")
                                            .take(MAX_CUSTOM_CHAT_FILTER_CHARS)
                                    },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text(stringResource(R.string.settings_chat_custom_filter_hint)) },
                                    singleLine = true,
                                )
                                SyButton(
                                    text = stringResource(R.string.settings_chat_custom_filter_add),
                                    onClick = {
                                        if (viewModel.addChatStyleGuardPhrase(candidate)) {
                                            customChatFilterDraft = ""
                                        }
                                    },
                                    enabled = canAddFilter,
                                    size = SyButtonSize.Small,
                                )
                            }
                            customFilters.forEach { phrase ->
                                ChatFilterPhraseRow(
                                    phrase = phrase,
                                    removeLabel = stringResource(R.string.settings_chat_custom_filter_remove),
                                    onRemove = { viewModel.removeChatStyleGuardPhrase(phrase) },
                                )
                            }

                            Text(
                                stringResource(R.string.settings_chat_persona_filters_title, personaFilters.size),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.text,
                            )
                            if (personaFilters.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_chat_persona_filters_empty),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.textTertiary,
                                )
                            } else {
                                personaFilters.forEach { phrase -> ChatFilterPhraseRow(phrase = phrase) }
                            }

                            if (localHarness.styleGuardHits.isNotEmpty()) {
                                SyButton(
                                    text = stringResource(
                                        if (showChatPreferenceDiagnostics) {
                                            R.string.settings_chat_guard_hits_hide
                                        } else {
                                            R.string.settings_chat_guard_hits_show
                                        },
                                        localHarness.styleGuardHits.size,
                                    ),
                                    onClick = {
                                        showChatPreferenceDiagnostics = !showChatPreferenceDiagnostics
                                    },
                                    variant = SyButtonVariant.Ghost,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (showChatPreferenceDiagnostics) {
                                    Text(
                                        stringResource(R.string.settings_chat_guard_hits_title),
                                        style = DsType.small13Strong.withReadingWeight(),
                                        color = colors.text,
                                    )
                                    localHarness.styleGuardHits.takeLast(8).asReversed().forEach { hit ->
                                        ChatFilterPhraseRow(phrase = hit)
                                    }
                                    SyButton(
                                        text = stringResource(R.string.settings_chat_guard_hits_clear),
                                        onClick = {
                                            viewModel.clearChatStyleGuardHits()
                                            showChatPreferenceDiagnostics = false
                                        },
                                        variant = SyButtonVariant.Ghost,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }

                    SettingsDestination.MODEL_PERFORMANCE -> ModelPerformanceSettingsPage()

                    SettingsDestination.ACCOUNT -> {
                        val account by viewModel.chatGptState.collectAsStateWithLifecycle()
                        ChatGptAccountPanel(account, localHarness, viewModel, toast.second)
                    }

                    SettingsDestination.MODELS -> {
                        var addModelRequested by remember { mutableStateOf(false) }
                        SyButton(
                            text = stringResource(R.string.local_api_model_add),
                            onClick = { addModelRequested = true },
                            modifier = Modifier.fillMaxWidth(),
                            icon = FeatherIcons.Plus,
                        )
                        AppSettingsRow(icon = SyEntryIcons.Account,
                            title = stringResource(R.string.settings_page_model_usage),
                            onClick = { openPage(SettingsDestination.MODEL_USAGE) })
                        LocalModelSettingsCard(
                            local = localHarness,
                            viewModel = viewModel,
                            report = toast.second,
                            requestAddModel = addModelRequested,
                            onAddModelRequestHandled = { addModelRequested = false },
                        )
                    }

                    SettingsDestination.MODEL_USAGE -> {
                        AppSettingsSection {
                            AppSettingsRow(
                                icon = SyEntryIcons.ChatStyle,
                                title = stringResource(R.string.settings_page_pricing),
                                reservedEntryHeight = 80.dp,
                                onClick = { openPage(SettingsDestination.PRICING) },
                            )
                            AppSettingsRow(
                                icon = SyEntryIcons.Usage,
                                title = stringResource(R.string.usage_calculation_title),
                                reservedEntryHeight = 80.dp,
                                onClick = { openPage(SettingsDestination.USAGE) },
                            )
                        }
                    }

                    SettingsDestination.MINIMAX_QUOTA -> {
                        MiniMaxQuotaPage(
                            profile = localHarness.miniMaxQuotaProfile,
                            loadingProfile = localHarness.loading,
                            onConfigureModel = { openPage(SettingsDestination.MODELS) },
                            queryQuota = viewModel::queryMiniMaxQuota,
                        )
                    }

                    SettingsDestination.PRICING -> {
                        DeepSeekPricingCard(deepSeekPricing, viewModel)
                    }

                    SettingsDestination.USAGE -> {
                        UsageCalculationPage(
                            usage = localHarness.usage,
                            onOpenPricing = { openPage(SettingsDestination.PRICING) },
                            analytics = usageAnalytics,
                            configuredModels = localHarness.modelProfiles.map { it.model },
                            selectedModel = selectedUsageModel,
                            onSelectModel = { selectedUsageModel = it },
                            onOpenGroup = { kind, key, title ->
                                usageDetailSelection = UsageDetailSelection.Group(kind, key, title)
                                usageDetailBackSelection = null
                                openPage(SettingsDestination.USAGE_DETAIL)
                            },
                        )
                    }

                    SettingsDestination.USAGE_LOG -> {
                        UsageLogPage(
                            analytics = usageAnalytics,
                            selectedModel = selectedUsageModel,
                            onOpenRequest = { requestId ->
                                usageDetailSelection = UsageDetailSelection.Request(requestId)
                                usageDetailBackSelection = null
                                openPage(SettingsDestination.USAGE_DETAIL)
                            },
                        )
                    }

                    SettingsDestination.USAGE_DETAIL -> {
                        when (val selection = usageDetailSelection) {
                            is UsageDetailSelection.Group -> {
                                val detail by produceState<TokenUsageGroupDetail?>(
                                    initialValue = null,
                                    selection,
                                    usageAnalytics,
                                    selectedUsageModel,
                                ) {
                                    value = viewModel.usageGroupDetail(selection.kind, selection.key, selectedUsageModel)
                                }
                                UsageGroupDetailPage(
                                    detail = detail,
                                    onOpenRequest = { requestId ->
                                        usageDetailBackSelection = selection
                                        usageDetailSelection = UsageDetailSelection.Request(requestId)
                                    },
                                )
                            }
                            is UsageDetailSelection.Request -> {
                                val record by produceState<TokenUsageRecord?>(
                                    initialValue = null,
                                    selection,
                                    usageAnalytics,
                                ) {
                                    value = viewModel.usageRecord(selection.requestId)
                                }
                                UsageRequestDetailPage(record)
                            }
                            null -> UsageRequestDetailPage(null)
                        }
                    }

                    SettingsDestination.MEMORY -> {
                        SettingsGroupTitle(stringResource(R.string.settings_memory_status))
                        MemoryOverviewCard(localHarness)
                        AppSettingsSection {
                            AppSettingsRow(SyEntryIcons.ChatStyle, stringResource(R.string.advanced_memory_settings),
                                onClick = { openPage(SettingsDestination.MEMORY_SETTINGS) })
                            AppSettingsDivider()
                            AppSettingsRow(SyEntryIcons.Memory, stringResource(R.string.advanced_manage_memory),
                                value = stringResource(R.string.advanced_memory_active_count, memoryManagement.totalMatching),
                                onClick = { openPage(SettingsDestination.MEMORY_MANAGEMENT) })
                        }
                    }
                    SettingsDestination.MEMORY_SETTINGS -> {
                        LocalMemorySettingsCard(localHarness, viewModel, toast.second)
                    }
                    SettingsDestination.MEMORY_MANAGEMENT -> {
                        MemoryManagementCard(
                            memoryManagement, viewModel, toast.second,
                            focusedSource = !showAllMemories && !memorySourceSessionId.isNullOrBlank() &&
                                !memorySourceMessageId.isNullOrBlank(),
                            onShowAll = { showAllMemories = true },
                        )
                    }

                    SettingsDestination.PERMISSIONS -> {
                        SettingsCard(stringResource(R.string.settings_approval_mode_title)) {
                            Text(
                                stringResource(R.string.settings_approval_mode_subtitle),
                                style = DsType.small13.withReadingWeight(),
                                color = colors.textSecondary,
                            )
                            SySegmented(
                                segments = listOf(
                                    SySegment(LocalApprovalMode.DEFAULT.name, stringResource(R.string.settings_approval_mode_default)),
                                    SySegment(LocalApprovalMode.MANUAL.name, stringResource(R.string.settings_approval_mode_manual)),
                                    SySegment(LocalApprovalMode.AUTO.name, stringResource(R.string.settings_approval_mode_auto)),
                                ),
                                selectedKey = approvalMode.name,
                                onSelect = { selected ->
                                    LocalApprovalMode.entries.firstOrNull { it.name == selected }
                                        ?.let(viewModel::configureApprovalMode)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                stretch = true,
                            )
                            Text(
                                stringResource(
                                    when (approvalMode) {
                                        LocalApprovalMode.DEFAULT -> R.string.settings_approval_default_hint
                                        LocalApprovalMode.MANUAL -> R.string.settings_approval_manual_hint
                                        LocalApprovalMode.AUTO -> R.string.settings_approval_auto_hint
                                    },
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.textSecondary,
                            )
                        }
                        DeviceCapabilitiesCard(deviceCapabilities, viewModel, requestPostingPermission)
                    }

                    SettingsDestination.NOTIFICATIONS -> {
                        NotificationPostingPermissionCard(deviceCapabilities, viewModel, requestPostingPermission)
                        SettingsCard(stringResource(R.string.settings_notifications_group_jobs)) {
                            ToggleRow(
                                stringResource(R.string.settings_notifications_local_jobs),
                                settings.notifyLocalJobs,
                            ) { viewModel.set { it.copy(notifyLocalJobs = !it.notifyLocalJobs) } }
                            ToggleRow(
                                stringResource(R.string.settings_notification_results),
                                settings.notifyJobResults,
                                stringResource(R.string.settings_notification_results_hint),
                            ) { viewModel.set { it.copy(notifyJobResults = !it.notifyJobResults) } }
                            ToggleRow(
                                stringResource(R.string.settings_notification_failures),
                                settings.notifyJobFailures,
                                stringResource(R.string.settings_notification_failures_hint),
                            ) { viewModel.set { it.copy(notifyJobFailures = !it.notifyJobFailures) } }
                            ToggleRow(
                                stringResource(R.string.settings_notification_needs_action),
                                settings.notifyNeedsAction,
                                stringResource(R.string.settings_notification_needs_action_hint),
                            ) { viewModel.set { it.copy(notifyNeedsAction = !it.notifyNeedsAction) } }
                            ToggleRow(
                                stringResource(R.string.settings_notification_chat_messages),
                                settings.notifyChatMessages,
                                stringResource(R.string.settings_notification_chat_messages_hint),
                            ) { viewModel.set { it.copy(notifyChatMessages = !it.notifyChatMessages) } }
                        }
                        SettingsCard(stringResource(R.string.settings_notification_quiet_title)) {
                            ToggleRow(
                                stringResource(R.string.settings_notification_quiet_enabled),
                                settings.notificationQuietHoursEnabled,
                                stringResource(R.string.settings_notification_quiet_hint),
                            ) { viewModel.set { it.copy(notificationQuietHoursEnabled = !it.notificationQuietHoursEnabled) } }
                            if (settings.notificationQuietHoursEnabled) {
                                SySegmented(
                                    segments = listOf(
                                        SySegment("23-7", stringResource(R.string.settings_notification_quiet_23_7)),
                                        SySegment("22-8", stringResource(R.string.settings_notification_quiet_22_8)),
                                    ),
                                    selectedKey = if (settings.notificationQuietStartMinute == 1320 &&
                                        settings.notificationQuietEndMinute == 480) "22-8" else "23-7",
                                    onSelect = { selected ->
                                        viewModel.set {
                                            if (selected == "22-8") it.copy(
                                                notificationQuietStartMinute = 1320,
                                                notificationQuietEndMinute = 480,
                                            ) else it.copy(
                                                notificationQuietStartMinute = 1380,
                                                notificationQuietEndMinute = 420,
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    stretch = true,
                                )
                            }
                        }
                    }

                    SettingsDestination.ADVANCED -> {
                        AppSettingsSection {
                            AppSettingsRow(
                                icon = SyEntryIcons.SessionStorage,
                                title = stringResource(R.string.settings_local_session_storage),
                                onClick = { openPage(SettingsDestination.SESSION_STORAGE) },
                            )
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = SyEntryIcons.Advanced,
                                title = stringResource(R.string.settings_runtime_diagnostics),
                                onClick = { openPage(SettingsDestination.DIAGNOSTICS) },
                            )
                        }
                    }

                    SettingsDestination.SESSION_STORAGE -> {
                        AppSettingsSection {
                            AppSettingsRow(icon = SyEntryIcons.ChatStyle,
                                title = stringResource(R.string.settings_page_session),
                                onClick = { openPage(SettingsDestination.SESSION) })
                        }
                        LocalSessionStorageCard(
                            status = localSessionStorageStatus,
                            busy = localSessionStorageBusy,
                            onCompact = {
                                scope.launch {
                                    localSessionStorageBusy = true
                                    try {
                                        localSessionStorageStatus = viewModel.compactLocalSessionStorage()
                                        toast.second(storageCompacted)
                                    } catch (error: Exception) {
                                        AppLog.failure("Settings", "compact_session_archive", error)
                                        toast.second(storageCompactFailed)
                                    } finally {
                                        localSessionStorageBusy = false
                                    }
                                }
                            },
                            onExport = { sessionStorageExporter.launch("777-local-sessions.zip") },
                            onRestore = { sessionStorageImporter.launch(arrayOf("application/zip", "application/octet-stream")) },
                            onCancelRestore = {
                                scope.launch {
                                    localSessionStorageBusy = true
                                    try {
                                        if (viewModel.cancelStagedSessionRestore()) {
                                            toast.second("已取消未开始的备份恢复")
                                            localSessionStorageStatus = viewModel.localSessionStorageStatus()
                                        }
                                    } catch (error: Exception) {
                                        toast.second(error.message ?: "无法取消已开始的恢复")
                                    } finally {
                                        localSessionStorageBusy = false
                                    }
                                }
                            },
                            onCleanup = onClose,
                        )
                    }

                    SettingsDestination.DIAGNOSTICS -> {
                        AppSettingsSection {
                            AppSettingsRow(
                                icon = FeatherIcons.GitBranch,
                                title = stringResource(R.string.settings_network_diagnostic),
                                onClick = { showDiagnostic = true },
                            )
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = FeatherIcons.Device,
                                title = stringResource(R.string.settings_environment_capabilities),
                                onClick = { showEnvironment = true },
                            )
                            AppSettingsDivider()
                            AppSettingsRow(
                                icon = FeatherIcons.Info,
                                title = stringResource(R.string.settings_export_diagnostics),
                                reservedEntryHeight = 88.dp,
                                onClick = { diagnosticExporter.launch("777-diagnostics.txt") },
                            )
                        }
                    }

                }

                Spacer(Modifier.height(SyMetric.gapPage))
                }
                }
            }
            SyToastHost(toast, modifier = Modifier.fillMaxWidth())
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
            onExport = { diagnosticExporter.launch("777-diagnostics.txt") },
        )
    }


}

/** One settings group as a raised card, so groups read as blocks rather than a running list. */
@Composable
private fun ChatFilterPhraseRow(
    phrase: String,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Text(
            text = "• " + phrase,
            style = DsType.caption11.withReadingWeight(),
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (removeLabel != null && onRemove != null) {
            SyButton(
                text = removeLabel,
                onClick = onRemove,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
        }
    }
}

/** The + menu's continuous scene is already behind this page.
 * These two visual settings pages use open sections, not nested L3 glass cards.
 * Other settings retain SettingsCard's existing material and behavior.
 */
@Composable
internal fun SettingsSeamlessGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("settingsSeamlessGroup"),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        SettingsGroupTitle(title)
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapXs),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        ) {
            content()
        }
    }
}

@Composable
internal fun SettingsCard(title: String, content: @Composable () -> Unit) {
    // Lightweight settings share the same canvas. Explicit data/approval cards keep their own material.
    SettingsSeamlessGroup(title, content)
}

/** 更新检查是设置页内的即时操作，不占用独立导航层级。 */
@Composable
internal fun UpdateCheckSettingsRow(
    updateStatus: String?,
    onCheckUpdate: () -> Unit,
) {
    AppSettingsRow(
        icon = SyEntryIcons.Updates,
        title = stringResource(R.string.settings_update_check),
        subtitle = updateStatus,
        onClick = onCheckUpdate,
        compact = true,
        showChevron = false,
    )
}

private const val MAX_CUSTOM_CHAT_FILTERS = 50
private const val MAX_CUSTOM_CHAT_FILTER_CHARS = 32

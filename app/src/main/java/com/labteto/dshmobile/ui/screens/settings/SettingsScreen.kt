package com.labteto.dshmobile.ui.screens.settings

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.ChatStyleGuard
import com.labteto.dshmobile.local.LocalSessionStorageStatus
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.AccentPalettes
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
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
 * App settings, grouped into cards.
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
    MODEL_USAGE,
    PRICING,
    USAGE,
    USAGE_LOG,
    USAGE_DETAIL,
    MEMORY,
    PERMISSIONS,
    NOTIFICATIONS,
    ADVANCED,
}

private fun SettingsDestination.parentDestination(): SettingsDestination? = when (this) {
    SettingsDestination.ROOT -> null
    SettingsDestination.SESSION,
    SettingsDestination.APPEARANCE,
    SettingsDestination.MODELS,
    SettingsDestination.MODEL_USAGE,
    SettingsDestination.MEMORY,
    SettingsDestination.PERMISSIONS,
    SettingsDestination.NOTIFICATIONS,
    SettingsDestination.ADVANCED -> SettingsDestination.ROOT
    SettingsDestination.CHAT -> SettingsDestination.SESSION
    SettingsDestination.PRICING,
    SettingsDestination.USAGE -> SettingsDestination.MODEL_USAGE
    SettingsDestination.USAGE_LOG -> SettingsDestination.USAGE
    SettingsDestination.USAGE_DETAIL -> SettingsDestination.USAGE
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
    onCheckUpdate: () -> Unit = {},
    updateStatus: String? = null,
    handleRootSystemBack: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val sessionSort by viewModel.sessionSort.collectAsStateWithLifecycle()
    val projectSettings by viewModel.projectSettings.collectAsStateWithLifecycle()
    val modelServices by viewModel.modelServices.collectAsStateWithLifecycle()
    val localHarness by viewModel.localHarnessState.collectAsStateWithLifecycle()
    val deepSeekPricing by viewModel.deepSeekPricing.collectAsStateWithLifecycle()
    val usageAnalytics by viewModel.usageAnalytics.collectAsStateWithLifecycle()
    val deviceCapabilities by viewModel.deviceCapabilities.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportFailed = stringResource(R.string.settings_export_diagnostics_failed)
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
                    } ?: error("无法打开导出文件")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toast.second(exportFailed)
            }
        }
    }
    var page by rememberSaveable(initialDestination) { mutableStateOf(initialDestination) }
    var usageDetailSelection by rememberSaveable(saver = usageDetailSelectionStateSaver) {
        mutableStateOf<UsageDetailSelection?>(null)
    }
    var usageDetailBackSelection by rememberSaveable(saver = usageDetailSelectionStateSaver) {
        mutableStateOf<UsageDetailSelection?>(null)
    }
    var usageDetailReturnPage by rememberSaveable { mutableStateOf(SettingsDestination.USAGE) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showDiagnostic by rememberSaveable { mutableStateOf(false) }
    var showEnvironment by rememberSaveable { mutableStateOf(false) }
    var environmentInfo by remember { mutableStateOf<String?>(null) }
    var customChatFilterDraft by rememberSaveable { mutableStateOf("") }
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
            } catch (_: Exception) {
                toast.second(storageExportFailed)
            } finally {
                localSessionStorageBusy = false
            }
        }
    }
    val scrollState = rememberScrollState()

    fun navigateBack() {
        if (page == SettingsDestination.USAGE_DETAIL) {
            if (usageDetailReturnPage == SettingsDestination.USAGE_DETAIL && usageDetailBackSelection != null) {
                usageDetailSelection = usageDetailBackSelection
                usageDetailBackSelection = null
                usageDetailReturnPage = SettingsDestination.USAGE
            } else {
                page = usageDetailReturnPage
            }
        } else {
            page.parentDestination()?.let { page = it } ?: onClose()
        }
    }

    BackHandler(enabled = handleRootSystemBack || page != SettingsDestination.ROOT) { navigateBack() }
    LaunchedEffect(connectionState.phase) {
        viewModel.refreshRemoteSettings()
    }
    LaunchedEffect(page) {
        scrollState.scrollTo(0)
        if (page == SettingsDestination.MEMORY) viewModel.refreshMemories()
        if (page == SettingsDestination.ADVANCED) {
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
        SettingsDestination.MODELS -> stringResource(R.string.settings_page_models)
        SettingsDestination.MODEL_USAGE -> stringResource(R.string.settings_page_model_usage)
        SettingsDestination.PRICING -> stringResource(R.string.settings_page_pricing)
        SettingsDestination.USAGE -> stringResource(R.string.usage_calculation_title)
        SettingsDestination.USAGE_LOG -> stringResource(R.string.usage_log_title)
        SettingsDestination.USAGE_DETAIL -> when (usageDetailSelection) {
            is UsageDetailSelection.Request -> stringResource(R.string.usage_request_detail)
            else -> stringResource(R.string.usage_group_detail)
        }
        SettingsDestination.MEMORY -> stringResource(R.string.settings_page_memory)
        SettingsDestination.PERMISSIONS -> stringResource(R.string.settings_page_permissions)
        SettingsDestination.NOTIFICATIONS -> stringResource(R.string.settings_page_notifications)
        SettingsDestination.ADVANCED -> stringResource(R.string.settings_page_advanced)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                DsTopBar(
                    title = title,
                    onBack = ::navigateBack,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = page == SettingsDestination.ROOT,
                    modifier = Modifier.padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
                    actionIcon = FeatherIcons.Clock.takeIf { page == SettingsDestination.USAGE },
                    actionContentDescription = stringResource(R.string.usage_log_open)
                        .takeIf { page == SettingsDestination.USAGE },
                    onAction = if (page == SettingsDestination.USAGE) {
                        { page = SettingsDestination.USAGE_LOG }
                    } else {
                        null
                    },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = DsSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xlarge),
                ) {
                when (page) {
                    SettingsDestination.ROOT -> {
                        Text(stringResource(R.string.settings_group_models), style = DsType.std14.withReadingWeight(), color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Cloud,
                                title = stringResource(R.string.settings_page_models),
                                subtitle = stringResource(R.string.settings_models_subtitle),
                                iconFamily = DsIconFamily.Accent,
                                value = localHarness.model.takeIf { it.isNotBlank() },
                                onClick = { page = SettingsDestination.MODELS },
                            )
                            DsCategoryRow(
                                icon = FeatherIcons.Clock,
                                title = stringResource(R.string.settings_page_model_usage),
                                subtitle = stringResource(R.string.settings_model_usage_subtitle),
                                iconFamily = DsIconFamily.Amber,
                                onClick = { page = SettingsDestination.MODEL_USAGE },
                            )
                        }

                        Text(stringResource(R.string.settings_group_experience), style = DsType.std14.withReadingWeight(), color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Memory,
                                title = stringResource(R.string.settings_page_memory),
                                subtitle = stringResource(R.string.settings_memory_subtitle),
                                iconFamily = DsIconFamily.Purple,
                                value = memories.size.toString(),
                                onClick = { page = SettingsDestination.MEMORY },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_page_appearance),
                                subtitle = stringResource(R.string.settings_appearance_reading_subtitle),
                                iconFamily = DsIconFamily.Cyan,
                                value = appearanceThemeLabel(settings.themePreference),
                                onClick = { page = SettingsDestination.APPEARANCE },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Chat,
                                title = stringResource(R.string.settings_page_session),
                                subtitle = stringResource(R.string.settings_session_subtitle),
                                iconFamily = DsIconFamily.Cyan,
                                value = stringResource(
                                    if (sessionSort == "updated") {
                                        R.string.chatlist_sort_updated
                                    } else {
                                        R.string.chatlist_sort_manual
                                    },
                                ),
                                onClick = { page = SettingsDestination.SESSION },
                            )
                        }

                        Text(stringResource(R.string.settings_group_system), style = DsType.std14.withReadingWeight(), color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.PhoneAndroid,
                                title = stringResource(R.string.settings_page_permissions),
                                subtitle = stringResource(R.string.settings_permissions_subtitle),
                                iconFamily = DsIconFamily.Green,
                                onClick = { page = SettingsDestination.PERMISSIONS },
                                trailing = {
                                    StateDot(deviceCapabilitiesState(deviceCapabilities))
                                },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Notifications,
                                title = stringResource(R.string.settings_page_notifications),
                                subtitle = stringResource(R.string.settings_notifications_subtitle),
                                iconFamily = DsIconFamily.Amber,
                                value = enabledNotificationCount(settings).toString(),
                                onClick = { page = SettingsDestination.NOTIFICATIONS },
                            )
                        }

                        Text(stringResource(R.string.settings_group_maintenance), style = DsType.std14.withReadingWeight(), color = colors.labelTertiary)
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_page_advanced),
                                subtitle = stringResource(R.string.settings_advanced_subtitle),
                                iconFamily = DsIconFamily.Neutral,
                                onClick = { page = SettingsDestination.ADVANCED },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.CloudDownload,
                                title = stringResource(R.string.settings_update_check),
                                subtitle = updateStatus,
                                iconFamily = DsIconFamily.Cyan,
                                onClick = onCheckUpdate,
                            )
                        }
                        Text(
                            "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelCaption,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = DsSpacing.small)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                    }

                    SettingsDestination.SESSION -> {
                        SettingsCard(stringResource(R.string.chatlist_title), FeatherIcons.Clock) {
                            ToggleRow(
                                stringResource(R.string.chatlist_sort_updated),
                                sessionSort == "updated",
                                stringResource(R.string.chatlist_sort_manual),
                            ) { viewModel.setSessionSortByRecency(sessionSort != "updated") }
                        }
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_chat_style_guard),
                                subtitle = stringResource(R.string.settings_chat_style_guard_hint),
                                iconFamily = DsIconFamily.Purple,
                                value = stringResource(
                                    if (localHarness.chatStyleGuardEnabled) {
                                        R.string.common_enabled
                                    } else {
                                        R.string.common_disabled
                                    },
                                ),
                                onClick = { page = SettingsDestination.CHAT },
                            )
                        }
                    }

                    SettingsDestination.APPEARANCE -> {
                        SettingsCard(stringResource(R.string.settings_appearance_preview), Icons.Outlined.Tune) {
                            AppearanceReadingPreview()
                        }
                        SettingsCard(stringResource(R.string.settings_appearance), Icons.Outlined.Tune) {
                            AppearanceRow(settings) { mode -> viewModel.set { it.copy(themePreference = mode) } }
                            AccentThemeRow(settings) { key -> viewModel.set { it.copy(accentTheme = key) } }
                            ReadingPreferencesRow(
                                settings = settings,
                                onTextScaleChange = { value ->
                                    viewModel.set { it.copy(textScale = value.coerceIn(0.9f, 1.3f)) }
                                },
                                onTextWeightChange = { value ->
                                    viewModel.set { it.copy(textWeightAdjustment = value.coerceIn(0, 2)) }
                                },
                                onTransparencyChange = { value ->
                                    viewModel.set {
                                        it.copy(wallpaperSurfaceTransparency = value.coerceIn(0f, 1f))
                                    }
                                },
                            )
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
                    }

                    SettingsDestination.CHAT -> {
                        val builtInFilters = ChatStyleGuard.bannedPhrases
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

                        SettingsCard(stringResource(R.string.settings_page_chat), Icons.Outlined.Tune) {
                            ToggleRow(
                                stringResource(R.string.settings_chat_style_guard),
                                localHarness.chatStyleGuardEnabled,
                                stringResource(R.string.settings_chat_style_guard_hint),
                            ) {
                                viewModel.configureChatStyleGuard(!localHarness.chatStyleGuardEnabled)
                            }

                            if (!localHarness.chatStyleGuardEnabled) {
                                Text(
                                    stringResource(R.string.settings_chat_filter_disabled_notice),
                                    style = DsType.small13.withReadingWeight(),
                                    color = colors.labelSecondary,
                                )
                            }

                            Text(
                                stringResource(R.string.settings_chat_builtin_filters_title, builtInFilters.size),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                            )
                            builtInFilters.forEach { phrase -> ChatFilterPhraseRow(phrase = phrase) }

                            Text(
                                stringResource(R.string.settings_chat_custom_filters_title, customFilters.size),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                            )
                            Text(
                                stringResource(
                                    R.string.settings_chat_filter_limits,
                                    MAX_CUSTOM_CHAT_FILTERS,
                                    MAX_CUSTOM_CHAT_FILTER_CHARS,
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelTertiary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            ) {
                                TextField(
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
                                DsButton(
                                    text = stringResource(R.string.settings_chat_custom_filter_add),
                                    onClick = {
                                        if (viewModel.addChatStyleGuardPhrase(candidate)) {
                                            customChatFilterDraft = ""
                                        }
                                    },
                                    enabled = canAddFilter,
                                    size = com.labteto.dshmobile.ui.components.DsButtonSize.Small,
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
                                color = colors.labelPrimary,
                            )
                            if (personaFilters.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_chat_persona_filters_empty),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.labelTertiary,
                                )
                            } else {
                                personaFilters.forEach { phrase -> ChatFilterPhraseRow(phrase = phrase) }
                            }

                            Text(
                                stringResource(R.string.settings_chat_guard_hits_title),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                            )
                            if (localHarness.styleGuardHits.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_chat_guard_hits_empty),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.labelTertiary,
                                )
                            } else {
                                localHarness.styleGuardHits.takeLast(8).asReversed().forEach { hit ->
                                    ChatFilterPhraseRow(phrase = hit)
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

                    SettingsDestination.MODELS -> {
                        LocalModelSettingsCard(localHarness, viewModel, toast.second)
                        ModelServicesCard(modelServices, viewModel)
                    }

                    SettingsDestination.MODEL_USAGE -> {
                        DsGroupCard {
                            DsCategoryRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_page_pricing),
                                subtitle = stringResource(R.string.settings_pricing_subtitle),
                                iconFamily = DsIconFamily.Amber,
                                onClick = { page = SettingsDestination.PRICING },
                            )
                            DsCategoryRow(
                                icon = FeatherIcons.Clock,
                                title = stringResource(R.string.usage_calculation_title),
                                subtitle = stringResource(R.string.usage_calculation_subtitle),
                                iconFamily = DsIconFamily.Cyan,
                                onClick = { page = SettingsDestination.USAGE },
                            )
                        }
                    }

                    SettingsDestination.PRICING -> {
                        DeepSeekPricingCard(deepSeekPricing, viewModel)
                    }

                    SettingsDestination.USAGE -> {
                        UsageCalculationPage(
                            usage = localHarness.usage,
                            onOpenPricing = { page = SettingsDestination.PRICING },
                            analytics = usageAnalytics,
                            onOpenGroup = { kind, key, title ->
                                usageDetailSelection = UsageDetailSelection.Group(kind, key, title)
                                usageDetailReturnPage = SettingsDestination.USAGE
                                page = SettingsDestination.USAGE_DETAIL
                            },
                        )
                    }

                    SettingsDestination.USAGE_LOG -> {
                        UsageLogPage(
                            analytics = usageAnalytics,
                            onOpenRequest = { requestId ->
                                usageDetailSelection = UsageDetailSelection.Request(requestId)
                                usageDetailReturnPage = SettingsDestination.USAGE_LOG
                                page = SettingsDestination.USAGE_DETAIL
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
                                ) {
                                    value = viewModel.usageGroupDetail(selection.kind, selection.key)
                                }
                                UsageGroupDetailPage(
                                    detail = detail,
                                    onOpenRequest = { requestId ->
                                        usageDetailBackSelection = selection
                                        usageDetailSelection = UsageDetailSelection.Request(requestId)
                                        usageDetailReturnPage = SettingsDestination.USAGE_DETAIL
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
                        MemoryOverviewCard(localHarness, memories.size)
                        LocalMemorySettingsCard(localHarness, viewModel, toast.second)
                        MemoryManagementCard(memories, viewModel, toast.second)
                    }

                    SettingsDestination.PERMISSIONS -> {
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

                    SettingsDestination.NOTIFICATIONS -> {
                        // 两类通知的打扰逻辑不同：智能体反馈 vs 后台任务，分组呈现
                        SettingsCard(stringResource(R.string.settings_notifications_group_agent), Icons.Outlined.Notifications) {
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
                        }
                        SettingsCard(stringResource(R.string.settings_notifications_group_jobs), Icons.Outlined.Notifications) {
                            ToggleRow(
                                stringResource(R.string.settings_notifications_local_jobs),
                                settings.notifyLocalJobs,
                                stringResource(R.string.settings_notifications_local_jobs_hint),
                            ) { viewModel.set { it.copy(notifyLocalJobs = !it.notifyLocalJobs) } }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = DsShapes.row,
                            color = colors.warnTertiary,
                        ) {
                            Text(
                                stringResource(R.string.settings_notifications_usage_hint),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.warnLabel,
                                modifier = Modifier.padding(DsSpacing.medium),
                            )
                        }
                    }

                    SettingsDestination.ADVANCED -> {
                        LocalAgentSettingsCard(localHarness, viewModel, toast.second)
                        ProjectSettingsCard(projectSettings, viewModel, toast.second)
                        LocalSessionStorageCard(
                            status = localSessionStorageStatus,
                            busy = localSessionStorageBusy,
                            onCompact = {
                                scope.launch {
                                    localSessionStorageBusy = true
                                    try {
                                        localSessionStorageStatus = viewModel.compactLocalSessionStorage()
                                        toast.second(storageCompacted)
                                    } catch (_: Exception) {
                                        toast.second(storageCompactFailed)
                                    } finally {
                                        localSessionStorageBusy = false
                                    }
                                }
                            },
                            onExport = { sessionStorageExporter.launch("777-local-sessions.zip") },
                            onCleanup = onClose,
                        )
                        SettingsCard(stringResource(R.string.settings_runtime_diagnostics), Icons.Outlined.Info) {
                            DsCategoryRow(
                                icon = Icons.Outlined.Link,
                                title = stringResource(R.string.settings_network_diagnostic),
                                iconFamily = DsIconFamily.Cyan,
                                onClick = { showDiagnostic = true },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.PhoneAndroid,
                                title = stringResource(R.string.settings_environment_capabilities),
                                iconFamily = DsIconFamily.Neutral,
                                onClick = { showEnvironment = true },
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Info,
                                title = stringResource(R.string.settings_export_diagnostics),
                                subtitle = stringResource(R.string.settings_export_diagnostics_hint),
                                onClick = { diagnosticExporter.launch("777-diagnostics.txt") },
                            )
                        }
                    }

                }

                Spacer(Modifier.height(DsSpacing.xlarge))
                }
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
            onExport = { diagnosticExporter.launch("777-diagnostics.txt") },
        )
    }

    if (showDisconnectDialog) {
        DsDialog(
            title = stringResource(R.string.settings_connection_disconnect_confirm),
            onDismiss = { showDisconnectDialog = false },
        ) {
            Text(
                stringResource(R.string.settings_connection_disconnect_message),
                style = DsType.std14.withReadingWeight(),
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
private fun ChatFilterPhraseRow(
    phrase: String,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            text = "• " + phrase,
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        if (removeLabel != null && onRemove != null) {
            DsButton(
                text = removeLabel,
                onClick = onRemove,
                variant = DsButtonVariant.Ghost,
                size = com.labteto.dshmobile.ui.components.DsButtonSize.Small,
            )
        }
    }
}

@Composable
internal fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().animateContentSize(DsAnimations.expand)) {
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
            Text(title, style = DsType.std14.withReadingWeight(), color = colors.labelTertiary)
        }
        DsGroupCard {
            content()
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsType.std14.withReadingWeight(), color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = DsType.caption11.withReadingWeight(),
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
    val statusText = when (connectionState.phase) {
        ConnectionPhase.CONNECTED -> stringResource(R.string.common_connected)
        ConnectionPhase.RECONNECTING -> stringResource(R.string.common_reconnecting)
        ConnectionPhase.CONNECTING -> stringResource(R.string.common_loading)
        ConnectionPhase.DISCONNECTED -> stringResource(R.string.common_offline)
    }
    val statusState = when (connectionState.phase) {
        ConnectionPhase.CONNECTED -> StateDotState.Done
        ConnectionPhase.RECONNECTING, ConnectionPhase.CONNECTING -> StateDotState.Running
        ConnectionPhase.DISCONNECTED -> StateDotState.Idle
    }
    val heroColor = when (connectionState.phase) {
        ConnectionPhase.CONNECTED -> colors.successTertiary
        ConnectionPhase.RECONNECTING -> colors.warnTertiary
        ConnectionPhase.CONNECTING -> colors.accentTertiary
        ConnectionPhase.DISCONNECTED -> colors.hoverSolid
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = heroColor,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(statusState)
                Spacer(Modifier.width(DsSpacing.small))
                Text(statusText, style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
            }
            connectionState.host?.let { host ->
                LabelledValue(
                    stringResource(R.string.settings_connection_host),
                    host.displayAddress,
                )
                LabelledValue(
                    stringResource(R.string.settings_connection_protocol),
                    if (host.useTls) "HTTPS" else "HTTP",
                )
            }
            if (connectionState.phase == ConnectionPhase.RECONNECTING && connectionState.attempts > 0) {
                LabelledValue(
                    stringResource(R.string.settings_connection_attempts),
                    connectionState.attempts.toString(),
                )
            }
        }
    }

    if (isConnected && connectionState.host != null) {
        DsButton(
            text = stringResource(R.string.settings_connection_disconnect),
            onClick = onDisconnect,
            variant = DsButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun enabledNotificationCount(settings: AppSettings): Int = listOf(
    settings.notifyTurnComplete,
    settings.notifyGoal,
    settings.notifyNeedsAction,
    settings.notifyLocalJobs,
).count { it }

private fun deviceCapabilitiesState(state: DeviceCapabilitiesState): StateDotState = when {
    state.loading -> StateDotState.Running
    state.error != null -> StateDotState.Error
    state.accessibility && state.notifications && state.virtualDisplay -> StateDotState.Done
    state.accessibility || state.notifications -> StateDotState.Warning
    else -> StateDotState.Idle
}

private const val MAX_CUSTOM_CHAT_FILTERS = 50
private const val MAX_CUSTOM_CHAT_FILTER_CHARS = 32

package com.labteto.dshmobile.ui.screens.settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.DeepSeekBillingSchedule
import com.labteto.dshmobile.local.DeepSeekPricePeriod
import com.labteto.dshmobile.local.DeepSeekPricingState
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.LocalModelCapability
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.DsValueRow
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
@Composable
internal fun LocalModelSettingsCard(
    local: LocalHarnessSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val modelSavedMessage = stringResource(R.string.advanced_model_saved)
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(LocalModelPresets.entries.first().model) }
    var baseUrl by remember { mutableStateOf(LocalModelPresets.entries.first().baseUrl) }
    var protocol by remember { mutableStateOf(LocalModelProtocol.CHAT_COMPLETIONS) }
    var custom by remember { mutableStateOf(false) }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var editorGeneration by remember { mutableStateOf(0L) }
    var apiKey by remember { mutableStateOf("") }
    var contextWindowTokens by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    val chatGpt by viewModel.chatGptState.collectAsStateWithLifecycle()
    val matchingRoutes = local.modelProfiles.filter {
        it.authKind == LocalModelAuthKind.API_KEY &&
            it.model == model.trim() && it.baseUrl == baseUrl.trim().trimEnd('/')
    }
    val savedProfile = editingProfileId?.let { id ->
        matchingRoutes.firstOrNull { it.id == id }
    }
    val savedRoute = savedProfile != null
    val selectedPreset = LocalModelPresets.find(model, baseUrl)
    val editRoute: (String, String, String?) -> Unit = { name, url, id ->
        editorGeneration++
        editingProfileId = id
        model = name
        baseUrl = url
        protocol = id?.let { profileId ->
            local.modelProfiles.firstOrNull {
                it.authKind == LocalModelAuthKind.API_KEY && it.id == profileId &&
                    it.model == name && it.baseUrl == url
            }?.protocol
        } ?: LocalModelPresets.protocolFor(name, url)
        contextWindowTokens = id?.let { profileId -> local.modelProfiles.firstOrNull { it.id == profileId }?.contextWindowTokensOverride?.toString() }.orEmpty()
        apiKey = ""
        testStatus = null
    }

    SettingsCard(stringResource(R.string.advanced_model_settings), Icons.Outlined.Cloud) {
        ChatGptAccountPanel(
            state = chatGpt,
            viewModel = viewModel,
            report = report,
            modelIdentityLocked = local.loading || local.running,
        )
        Text(stringResource(R.string.local_model_list_hint), style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary)
        if (local.modelProfiles.isEmpty()) {
            Text(stringResource(R.string.advanced_model_unconfigured), style = DsType.small13.withReadingWeight(),
                color = colors.labelTertiary)
        }
        local.error?.let { Text(it, style = DsType.small13.withReadingWeight(), color = colors.error) }
        local.modelProfiles.forEach { profile ->
            Surface(shape = DsShapes.row, color = colors.wallpaperSurface(WallpaperSurfaceLevel.INPUT),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                            Text(profile.displayName ?: profile.model, style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary,
                                modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (local.modelSelection.isActive(profile)) {
                                DsStatusPill(DsStatus.Done, stringResource(R.string.local_model_in_use))
                            }
                        }
                        val duplicateApiRoute = profile.authKind == LocalModelAuthKind.API_KEY &&
                            local.modelProfiles.count {
                                it.authKind == LocalModelAuthKind.API_KEY &&
                                    it.model == profile.model && it.baseUrl == profile.baseUrl
                            } > 1
                        Text(
                            if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
                                stringResource(R.string.chatgpt_model_source, profile.model)
                            } else if (duplicateApiRoute) {
                                "${profile.baseUrl} · ${profile.id.takeLast(6)}"
                            } else {
                                profile.baseUrl
                            },
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ModelCapabilityTags(
                            LocalModelPresets.clientCapabilitiesFor(profile.model, profile.baseUrl),
                        )
                    }
                    DsMenu(
                        anchor = { Text("⋯", style = DsType.large20.withReadingWeight(), color = colors.labelSecondary,
                            modifier = Modifier.padding(horizontal = DsSpacing.small)) },
                        items = listOfNotNull(
                            if (!local.modelSelection.isActive(profile))
                                MenuItem(text = stringResource(R.string.local_model_use),
                                    onClick = { viewModel.selectLocalModel(profile.id) }) else null,
                            if (profile.authKind == LocalModelAuthKind.API_KEY)
                                MenuItem(text = stringResource(R.string.local_model_edit), onClick = {
                                    editRoute(profile.model, profile.baseUrl, profile.id)
                                    custom = LocalModelPresets.entries.none {
                                        it.model == profile.model && it.baseUrl == profile.baseUrl
                                    }
                                    showEditor = true
                                }) else null,
                            if (profile.authKind == LocalModelAuthKind.API_KEY)
                                MenuItem(text = stringResource(R.string.local_model_remove), danger = true,
                                    onClick = { pendingRemoveId = profile.id }) else null,
                        ),
                    )
                }
            }
        }
        DsButton(stringResource(R.string.local_model_add), onClick = {
            editRoute(LocalModelPresets.entries.first().model, LocalModelPresets.entries.first().baseUrl, null)
            custom = false
            showEditor = true
        }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Add)
    }

    if (showEditor) {
        DsBottomSheet(
            title = stringResource(R.string.local_model_add),
            subtitle = stringResource(R.string.local_model_preset_hint),
            onDismiss = { showEditor = false; editorGeneration++ },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(stringResource(R.string.local_model_choose), style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary)
                DsMenu(
                    anchor = {
                        Surface(shape = DsShapes.row,
                            color = colors.wallpaperSurface(WallpaperSurfaceLevel.INPUT),
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(DsSpacing.comfortable),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (custom) stringResource(R.string.local_model_custom)
                                    else LocalModelPresets.entries.firstOrNull {
                                        it.model == model && it.baseUrl == baseUrl
                                    }?.let { "${it.provider} · ${it.model}" } ?: model,
                                    style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary,
                                    modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                        }
                    },
                    items = LocalModelPresets.entries.map { preset ->
                        MenuItem("${preset.provider} · ${preset.model}") {
                            custom = false
                            editRoute(preset.model, preset.baseUrl, null)
                        }
                    } + MenuItem(stringResource(R.string.local_model_custom)) {
                        custom = true
                        editRoute("", "", null)
                    },
                )
                if (custom) {
                    OutlinedTextField(model, onValueChange = { model = it.take(160); editingProfileId = null; editorGeneration++; testStatus = null },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.advanced_default_model)) })
                    OutlinedTextField(baseUrl, onValueChange = { baseUrl = it.take(1000); editingProfileId = null; editorGeneration++; testStatus = null },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.advanced_endpoint)) })
                    LocalModelContextWindowField(contextWindowTokens) { contextWindowTokens = it; editorGeneration++; testStatus = null }
                } else {
                    Text(baseUrl, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                    selectedPreset?.let { preset ->
                        Text(
                            stringResource(
                                R.string.local_model_request_endpoint,
                                when (protocol) {
                                    LocalModelProtocol.RESPONSES -> "${preset.baseUrl.trimEnd('/')}/responses"
                                    LocalModelProtocol.ANTHROPIC_MESSAGES -> "${preset.baseUrl.trimEnd('/')}/messages"
                                    LocalModelProtocol.CHAT_COMPLETIONS -> preset.chatEndpoint
                                },
                            ),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        preset.modelsEndpoint?.let { endpoint ->
                            Text(
                                stringResource(R.string.local_model_models_endpoint, endpoint),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelTertiary,
                            )
                        }
                        ModelCapabilityTags(LocalModelPresets.clientCapabilitiesFor(preset.model, preset.baseUrl))
                        if (preset.capabilities.any { it == LocalModelCapability.VIDEO || it == LocalModelCapability.AUDIO }) {
                            Text(stringResource(R.string.local_model_media_not_connected),
                                style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                        }
                    }
                }
                if (custom) LocalModelProtocolPicker(protocol) { protocol = it; editorGeneration++; testStatus = null }
                OutlinedTextField(apiKey, onValueChange = { apiKey = it.take(8000); editorGeneration++; testStatus = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(if (savedRoute) R.string.advanced_replace_model_key
                        else R.string.advanced_model_key)) },
                    supportingText = { if (savedRoute) Text(stringResource(R.string.local_model_keep_key)) },
                    visualTransformation = PasswordVisualTransformation())
                testStatus?.let { Text(it, style = DsType.small13.withReadingWeight(),
                    color = if (it.startsWith("连接成功")) colors.labelSecondary else colors.error) }
                DsButton(stringResource(if (testing) R.string.local_model_testing else R.string.local_model_test),
                    onClick = {
                        testing = true
                        testStatus = null
                        val testedModel = model
                        val testedUrl = baseUrl
                        val testedKey = apiKey
                        val testedProtocol = protocol
                        val testedProfileId = editingProfileId
                        val testedGeneration = editorGeneration
                        scope.launch {
                            try {
                                val result = viewModel.testLocalModel(testedKey, testedModel, testedUrl, testedProtocol, testedProfileId)
                                if (showEditor && editorGeneration == testedGeneration) testStatus = result
                            } finally {
                                testing = false
                            }
                        }
                    }, enabled = !testing && model.isNotBlank() && baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), variant = DsButtonVariant.Outline)
                DsButton(stringResource(R.string.advanced_save_model_settings), onClick = {
                    if (!savedRoute && apiKey.isBlank()) {
                        testStatus = "请填写该模型的密钥"
                    } else {
                        saving = true
                        val savedGeneration = editorGeneration
                        val savedKey = apiKey
                        val savedModel = model
                        val savedUrl = baseUrl
                        val savedProtocol = protocol
                        val savedProfileId = editingProfileId
                        val savedContextWindowOverride = contextWindowTokens.toIntOrNull() ?: 0
                        scope.launch {
                            try {
                                viewModel.saveLocalModel(savedKey, savedModel, savedUrl, savedProtocol, savedProfileId, savedContextWindowOverride)
                                if (showEditor && editorGeneration == savedGeneration) {
                                    apiKey = ""
                                    showEditor = false
                                }
                                report(modelSavedMessage)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (showEditor && editorGeneration == savedGeneration) testStatus = error.message ?: "保存失败"
                            } finally {
                                saving = false
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(),
                    enabled = !testing && !saving && model.isNotBlank() && baseUrl.isNotBlank() &&
                        (savedRoute || apiKey.isNotBlank()))
            }
        }
    }
    pendingRemoveId?.let { id ->
        DsBottomSheet(title = stringResource(R.string.local_model_remove),
            subtitle = stringResource(R.string.local_model_remove_confirm),
            onDismiss = { pendingRemoveId = null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(stringResource(R.string.common_cancel), onClick = { pendingRemoveId = null },
                    modifier = Modifier.weight(1f), variant = DsButtonVariant.Ghost)
                DsButton(stringResource(R.string.local_model_remove), onClick = {
                    viewModel.removeLocalModel(id)
                    pendingRemoveId = null
                }, modifier = Modifier.weight(1f), variant = DsButtonVariant.Danger)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCapabilityTags(capabilities: Set<LocalModelCapability>) {
    if (capabilities.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        capabilities.sortedBy { it.ordinal }.forEach { capability ->
            DsPill(text = stringResource(modelCapabilityLabel(capability)))
        }
    }
}

private fun modelCapabilityLabel(capability: LocalModelCapability): Int = when (capability) {
    LocalModelCapability.TEXT -> R.string.local_model_capability_text
    LocalModelCapability.IMAGE -> R.string.local_model_capability_image
    LocalModelCapability.VIDEO -> R.string.local_model_capability_video
    LocalModelCapability.AUDIO -> R.string.local_model_capability_audio
    LocalModelCapability.MUSIC -> R.string.local_model_capability_music
}

@Composable
internal fun DeepSeekPricingCard(
    state: DeepSeekPricingState,
    viewModel: SettingsViewModel,
) {
    val colors = DsTheme.colors
    val currentPeriod = DeepSeekBillingSchedule.periodAt(System.currentTimeMillis())
    val periodLabel = stringResource(
        if (currentPeriod == DeepSeekPricePeriod.PEAK) R.string.pricing_period_peak
        else R.string.pricing_period_off_peak,
    )
    val sourceLabel = if (state.lastUpdatedAt > 0L) {
        stringResource(
            R.string.pricing_updated_at,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(state.lastUpdatedAt)),
        )
    } else {
        stringResource(R.string.pricing_builtin_source)
    }

    SettingsCard(stringResource(R.string.pricing_deepseek_title), Icons.Outlined.Cloud) {
        Text(
            stringResource(R.string.pricing_current_period, periodLabel),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelPrimary,
        )
        Text(sourceLabel, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
        Text(
            stringResource(R.string.pricing_source_official),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )

        state.models.forEach { model ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = DsShapes.block,
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            ) {
                Column(
                    modifier = Modifier.padding(DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(model.displayName, style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
                    Text(
                        "${model.modelId} · ${model.version}",
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    Text(
                        stringResource(R.string.pricing_same_thinking),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                    // 表头：缓存命中 / 缓存未命中 / 输出
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1.5f))
                        Text(
                            stringResource(R.string.pricing_col_cache_hit),
                            style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        )
                        Text(
                            stringResource(R.string.pricing_col_cache_miss),
                            style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        )
                        Text(
                            stringResource(R.string.pricing_col_output),
                            style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        )
                    }
                    PriceTableRow(
                        periodLabel = stringResource(R.string.pricing_period_off_peak),
                        cacheHit = formatDeepSeekPrice(model.offPeak.cacheHitCnyPerMillion),
                        cacheMiss = formatDeepSeekPrice(model.offPeak.cacheMissCnyPerMillion),
                        output = formatDeepSeekPrice(model.offPeak.outputCnyPerMillion),
                        active = currentPeriod == DeepSeekPricePeriod.OFF_PEAK,
                    )
                    PriceTableRow(
                        periodLabel = stringResource(R.string.pricing_period_peak),
                        cacheHit = formatDeepSeekPrice(model.peak.cacheHitCnyPerMillion),
                        cacheMiss = formatDeepSeekPrice(model.peak.cacheMissCnyPerMillion),
                        output = formatDeepSeekPrice(model.peak.outputCnyPerMillion),
                        active = currentPeriod == DeepSeekPricePeriod.PEAK,
                    )
                }
            }
        }

        Text(
            stringResource(R.string.pricing_holiday_hint),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )
        state.error?.let { error ->
            Text(
                stringResource(R.string.pricing_refresh_failed, error),
                style = DsType.caption11.withReadingWeight(),
                color = colors.error,
            )
        }
        DsButton(
            text = stringResource(
                if (state.refreshing) R.string.pricing_refreshing else R.string.pricing_refresh,
            ),
            onClick = viewModel::refreshDeepSeekPricing,
            enabled = !state.refreshing,
            variant = DsButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 价格表行：时段标签 + 三列右对齐价格；当前计价时段整行提亮。 */
@Composable
private fun PriceTableRow(
    periodLabel: String,
    cacheHit: String,
    cacheMiss: String,
    output: String,
    active: Boolean,
) {
    val colors = DsTheme.colors
    val labelColor = if (active) colors.accent else colors.labelSecondary
    val priceColor = if (active) colors.labelPrimary else colors.labelSecondary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            periodLabel,
            style = DsType.small13.withReadingWeight(),
            color = labelColor,
            modifier = Modifier.weight(1.5f),
        )
        Text(cacheHit, style = DsType.small13.withReadingWeight(), color = priceColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(cacheMiss, style = DsType.small13.withReadingWeight(), color = priceColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(output, style = DsType.small13.withReadingWeight(), color = priceColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private fun formatDeepSeekPrice(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

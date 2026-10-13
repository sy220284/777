package com.labteto.dshmobile.ui.screens.settings


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.model.DeepSeekBillingSchedule
import com.labteto.dshmobile.local.model.DeepSeekPricePeriod
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalModelCapability
import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyMaterialFrame
import com.labteto.dshmobile.ui.shenyan.SyMaterialTier
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyMenu
import com.shenyan.designsystem.components.SyPill
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.shenyan.SyStatus
import com.labteto.dshmobile.ui.shenyan.SyStatusPill
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun LocalModelSettingsCard(
    local: LocalHarnessSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
    requestAddModel: Boolean,
    onAddModelRequestHandled: () -> Unit,
) {
    val colors = LocalSyColors.current
    val modelSavedMessage = stringResource(R.string.advanced_model_saved)
    val catalogEmptyMessage = stringResource(R.string.local_model_catalog_empty)
    val catalogSuccessFormat = stringResource(R.string.local_model_catalog_success)
    val catalogFailureFormat = stringResource(R.string.local_model_catalog_failure)
    val catalogUnavailable = stringResource(R.string.local_model_catalog_unavailable)
    val catalogTestFailureFormat = stringResource(R.string.local_model_catalog_test_failure)
    val scope = rememberCoroutineScope()
    val apiProfiles = local.modelProfiles.apiModelSettingsProfiles()
    var showEditor by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(LocalModelPresets.entries.first().model) }
    var baseUrl by remember { mutableStateOf(LocalModelPresets.entries.first().baseUrl) }
    var protocol by remember { mutableStateOf(LocalModelProtocol.CHAT_COMPLETIONS) }
    var custom by remember { mutableStateOf(false) }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var editorGeneration by remember { mutableStateOf(0L) }
    var apiKey by remember { mutableStateOf("") }
    var credentialSourceProfileId by remember { mutableStateOf<String?>(null) }
    var contextWindowTokens by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalogSearch by remember { mutableStateOf("") }
    val visibleCatalogModels = discoveredModels.asSequence()
        .filter { it.contains(catalogSearch.trim(), ignoreCase = true) }
        .take(60)
        .toList()
    var catalogStatus by remember { mutableStateOf<String?>(null) }
    var catalogError by remember { mutableStateOf(false) }
    var refreshingModels by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
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
        credentialSourceProfileId = id
        discoveredModels = emptyList()
        catalogSearch = ""
        catalogStatus = null
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

    val refreshModels: () -> Unit = refresh@{
        if (refreshingModels || saving || baseUrl.isBlank()) return@refresh
        refreshingModels = true
        catalogError = false
        catalogStatus = null
        val queriedKey = apiKey
        val queriedUrl = baseUrl
        val queriedProtocol = protocol
        val sourceProfileId = credentialSourceProfileId
        val generation = editorGeneration
        scope.launch {
            try {
                val found = viewModel.discoverApiModels(
                    queriedKey, queriedUrl, queriedProtocol, sourceProfileId,
                )
                if (showEditor && editorGeneration == generation) {
                    discoveredModels = found
                    catalogSearch = ""
                    catalogStatus = if (found.isEmpty()) {
                        catalogEmptyMessage
                    } else {
                        catalogSuccessFormat.format(found.size)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                if (showEditor && editorGeneration == generation) {
                    discoveredModels = emptyList()
                    catalogError = true
                    catalogStatus = catalogFailureFormat.format(catalogUnavailable)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (showEditor && editorGeneration == generation) {
                    discoveredModels = emptyList()
                    catalogError = true
                    catalogStatus = catalogFailureFormat.format(error.message ?: catalogUnavailable)
                }
            } finally {
                refreshingModels = false
            }
        }
    }

    LaunchedEffect(requestAddModel) {
        if (requestAddModel) {
            editRoute(LocalModelPresets.entries.first().model, LocalModelPresets.entries.first().baseUrl, null)
            custom = false
            showEditor = true
            onAddModelRequestHandled()
        }
    }

    SettingsCard(stringResource(R.string.local_api_models_title)) {
        val selectedProfiles = apiProfiles.count { local.modelSelection.isActive(it) }
        Text(
            stringResource(
                R.string.local_model_inventory_summary,
                apiProfiles.size,
                selectedProfiles,
            ),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.text,
        )
        Text(stringResource(R.string.local_model_list_hint), style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary)
        if (apiProfiles.isEmpty()) {
            Text(stringResource(R.string.advanced_model_unconfigured), style = DsType.small13.withReadingWeight(),
                color = colors.textTertiary)
        }
        local.error?.let { Text(it, style = DsType.small13.withReadingWeight(), color = colors.dangerInk) }
        apiProfiles.forEach { profile ->
            val isSelectedProfile = local.modelSelection.isActive(profile)
            Surface(
                shape = DsShapes.row,
                color = if (isSelectedProfile) colors.selected
                    else LocalSyColors.current.materialL1,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelectedProfile) colors.primary.copy(alpha = 0.45f) else colors.border,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                            Text(profile.displayName ?: profile.model, style = DsType.std14Strong.withReadingWeight(), color = colors.text,
                                modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (local.modelSelection.isActive(profile)) {
                                SyStatusPill(SyStatus.Done, stringResource(R.string.local_model_in_use))
                            }
                        }
                        val duplicateApiRoute = apiProfiles.count {
                            it.model == profile.model && it.baseUrl == profile.baseUrl
                        } > 1
                        Text(
                            if (duplicateApiRoute) {
                                "${profile.baseUrl} · ${profile.id.takeLast(6)}"
                            } else {
                                profile.baseUrl
                            },
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val modelContract = LocalEffectiveModelContracts.forProfile(profile)
                        ModelCapabilityTags(modelContract.clientModalities)
                        val creatorKinds = modelContract.mediaOffers.map { it.kind }.distinct()
                        val contextDescription = modelContract.documentedContextWindowTokens
                            ?.let { stringResource(R.string.local_model_policy_known_context, it) }
                            ?: stringResource(R.string.local_model_policy_unknown_context)
                        Text(
                            stringResource(
                                R.string.local_model_policy_work_budget,
                                contextDescription,
                                modelContract.executionBudget.workRequestsPerSlice,
                            ),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textSecondary,
                        )
                        if (creatorKinds.isNotEmpty()) {
                            val creativeLabels = mapOf(
                                LocalMediaKind.IMAGE to stringResource(R.string.media_studio_capability_image),
                                LocalMediaKind.VIDEO to stringResource(R.string.media_studio_capability_video),
                                LocalMediaKind.SPEECH to stringResource(R.string.media_studio_capability_speech),
                                LocalMediaKind.TRANSCRIBE to stringResource(R.string.media_studio_capability_transcribe),
                            )
                            Text(
                                stringResource(
                                    R.string.media_studio_model_capabilities,
                                    creatorKinds.joinToString(" · ") { kind -> creativeLabels.getValue(kind) },
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.textSecondary,
                            )
                        }
                    }
                    SyMenu(
                        anchor = { Text("⋯", style = DsType.large20.withReadingWeight(), color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = DsSpacing.small)) },
                        items = listOfNotNull(
                            if (!local.modelSelection.isActive(profile))
                                SyMenuItem(text = stringResource(R.string.local_model_use),
                                    onClick = { viewModel.selectLocalModel(profile.id) }) else null,
                            SyMenuItem(text = stringResource(R.string.local_model_edit), onClick = {
                                editRoute(profile.model, profile.baseUrl, profile.id)
                                custom = LocalModelPresets.entries.none {
                                    it.model == profile.model && it.baseUrl == profile.baseUrl
                                }
                                showEditor = true
                            }),
                            SyMenuItem(text = stringResource(R.string.local_model_remove), danger = true,
                                onClick = { pendingRemoveId = profile.id }),
                        ),
                    )
                }
            }
        }
    }
    SettingsCard(stringResource(R.string.local_model_image_mode_title)) {
        SySegmented(
            segments = listOf(
                SySegment(LocalImageInputMode.AUTO.name, stringResource(R.string.local_model_image_mode_auto)),
                SySegment(LocalImageInputMode.NATIVE.name, stringResource(R.string.local_model_image_mode_native)),
                SySegment(LocalImageInputMode.TOOL.name, stringResource(R.string.local_model_image_mode_tool)),
            ),
            selectedKey = local.imageInputMode.name,
            onSelect = { key ->
                runCatching { LocalImageInputMode.valueOf(key) }
                    .getOrNull()
                    ?.let(viewModel::configureImageInputMode)
            },
            modifier = Modifier.fillMaxWidth(),
            stretch = true,
        )
        Text(
            stringResource(
                when (local.imageInputMode) {
                    LocalImageInputMode.AUTO -> R.string.local_model_image_mode_auto_hint
                    LocalImageInputMode.NATIVE -> R.string.local_model_image_mode_native_hint
                    LocalImageInputMode.TOOL -> R.string.local_model_image_mode_tool_hint
                },
            ),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )
    }

    if (showEditor) {
        SyResponsiveSheet(
            title = stringResource(if (editingProfileId == null) R.string.local_model_add else R.string.local_model_edit),
            subtitle = stringResource(R.string.local_model_preset_hint),
            onDismiss = { showEditor = false; editorGeneration++ },
            dismissEnabled = !saving,
            scrollable = true,
            footer = {
                SyButton(stringResource(R.string.advanced_save_model_settings), onClick = {
                    if (!savedRoute && apiKey.isBlank() && credentialSourceProfileId == null) {
                        testStatus = "请填写该模型的密钥"
                    } else {
                        saving = true
                        val savedGeneration = editorGeneration
                        val savedKey = apiKey
                        val savedModel = model
                        val savedUrl = baseUrl
                        val savedProtocol = protocol
                        val savedProfileId = editingProfileId
                        val savedCredentialSourceProfileId = credentialSourceProfileId
                        val savedContextWindowOverride = contextWindowTokens.toIntOrNull() ?: 0
                        scope.launch {
                            try {
                                viewModel.saveLocalModel(savedKey, savedModel, savedUrl, savedProtocol, savedProfileId, savedContextWindowOverride, savedCredentialSourceProfileId)
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
                }, modifier = Modifier.fillMaxWidth(), size = SyButtonSize.Large, loading = saving,
                    enabled = !testing && !saving && !refreshingModels && model.isNotBlank() && baseUrl.isNotBlank() &&
                        (savedRoute || apiKey.isNotBlank() || credentialSourceProfileId != null))
            },
        ) {
            Column(Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(stringResource(R.string.local_model_choose), style = DsType.small13Strong.withReadingWeight(),
                    color = colors.text)
                SyMenu(
                    anchor = {
                        Surface(shape = DsShapes.row,
                            color = LocalSyColors.current.materialL1,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(DsSpacing.comfortable),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (custom) model.ifBlank { stringResource(R.string.local_model_custom) }
                                    else LocalModelPresets.entries.firstOrNull {
                                        it.model == model && it.baseUrl == baseUrl
                                    }?.let { "${it.provider} · ${it.model}" } ?: model,
                                    style = DsType.std14Strong.withReadingWeight(), color = colors.text,
                                    modifier = Modifier.weight(1f))
                                Icon(FeatherIcons.ChevronRight, contentDescription = null)
                            }
                        }
                    },
                    items = LocalModelPresets.entries.map { preset ->
                        SyMenuItem("${preset.provider} · ${preset.model}") {
                            custom = false
                            editRoute(preset.model, preset.baseUrl, null)
                        }
                    } + SyMenuItem(stringResource(R.string.local_model_custom)) {
                        custom = true
                        editRoute("", "", null)
                    },
                )
                if (custom) {
                    SyTextField(model, onValueChange = {
                        if (editingProfileId != null) credentialSourceProfileId = editingProfileId
                        model = it.take(160); editingProfileId = null; editorGeneration++; testStatus = null
                    },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.advanced_default_model)) })
                    SyTextField(baseUrl, onValueChange = {
                        baseUrl = it.take(1000); editingProfileId = null
                        credentialSourceProfileId = null
                        discoveredModels = emptyList(); catalogStatus = null
                        editorGeneration++; testStatus = null
                    },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.advanced_endpoint)) })
                    LocalModelContextWindowField(contextWindowTokens) { contextWindowTokens = it; editorGeneration++; testStatus = null }
                } else {
                    Text(baseUrl, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
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
                            color = colors.textTertiary,
                        )
                        preset.modelsEndpoint?.let { endpoint ->
                            Text(
                                stringResource(R.string.local_model_models_endpoint, endpoint),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.textTertiary,
                            )
                        }
                        ModelCapabilityTags(LocalModelPresets.clientCapabilitiesFor(preset.model, preset.baseUrl))
                        if (preset.capabilities.any { it == LocalModelCapability.VIDEO || it == LocalModelCapability.AUDIO }) {
                            Text(stringResource(R.string.local_model_media_not_connected),
                                style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
                        }
                    }
                }
                if (custom) LocalModelProtocolPicker(protocol) {
                    protocol = it; editorGeneration++; testStatus = null
                    discoveredModels = emptyList(); catalogStatus = null
                }
                SyTextField(apiKey, onValueChange = {
                    apiKey = it.take(8000); editorGeneration++; testStatus = null
                    discoveredModels = emptyList(); catalogStatus = null
                },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(if (savedRoute) R.string.advanced_replace_model_key
                        else R.string.advanced_model_key)) },
                    supportingText = { if (savedRoute) Text(stringResource(R.string.local_model_keep_key)) },
                    visualTransformation = PasswordVisualTransformation())
                SyButton(
                    if (refreshingModels) stringResource(R.string.local_model_catalog_fetching)
                    else stringResource(R.string.local_model_catalog_refresh),
                    onClick = refreshModels,
                    enabled = !refreshingModels && !testing && !saving && baseUrl.isNotBlank() &&
                        (apiKey.isNotBlank() || credentialSourceProfileId != null),
                    modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Outline,
                )
                catalogStatus?.let { status ->
                    Text(status, style = DsType.small13.withReadingWeight(),
                        color = if (catalogError) colors.dangerInk else colors.textSecondary)
                }
                if (discoveredModels.isNotEmpty()) {
                    if (discoveredModels.size > 20) {
                        SyTextField(catalogSearch, onValueChange = { catalogSearch = it.take(128) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.local_model_catalog_search)) })
                        if (discoveredModels.size > 60) {
                            Text(stringResource(R.string.local_model_catalog_search_hint),
                                style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
                        }
                    }
                    SyMenu(
                        anchor = {
                            Surface(shape = DsShapes.row,
                                color = LocalSyColors.current.materialL1,
                                modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(DsSpacing.comfortable),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.local_model_catalog_selector, discoveredModels.size),
                                        style = DsType.std14Strong.withReadingWeight(),
                                        color = colors.text, modifier = Modifier.weight(1f))
                                    Icon(FeatherIcons.ChevronRight, contentDescription = null)
                                }
                            }
                        },
                        items = visibleCatalogModels.map { discovered ->
                            SyMenuItem(discovered) {
                                if (editingProfileId != null) credentialSourceProfileId = editingProfileId
                                editingProfileId = null
                                model = discovered
                                custom = true
                                testStatus = null
                                editorGeneration++
                            }
                        },
                    )
                    Text(stringResource(R.string.local_model_catalog_hint),
                        style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
                }
                testStatus?.let { Text(it, style = DsType.small13.withReadingWeight(),
                    color = if (it.startsWith("连接成功")) colors.textSecondary else colors.dangerInk) }
                SyButton(stringResource(if (testing) R.string.local_model_testing else R.string.local_model_test),
                    onClick = {
                        testing = true
                        testStatus = null
                        val testedModel = model
                        val testedUrl = baseUrl
                        val testedKey = apiKey
                        val testedProtocol = protocol
                        val testedProfileId = editingProfileId
                        val testedCredentialSourceProfileId = credentialSourceProfileId
                        val testedGeneration = editorGeneration
                        // Model inventory is independent of inference support and should be
                        // discoverable immediately, even if the selected model is obsolete.
                        if (testedKey.isNotBlank() || testedCredentialSourceProfileId != null) {
                            refreshModels()
                        }
                        scope.launch {
                            try {
                                val result = viewModel.testLocalModel(testedKey, testedModel, testedUrl, testedProtocol, testedProfileId, testedCredentialSourceProfileId)
                                if (showEditor && editorGeneration == testedGeneration) testStatus = result
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (showEditor && editorGeneration == testedGeneration) {
                                    testStatus = catalogTestFailureFormat.format(error.message ?: catalogUnavailable)
                                }
                            } finally {
                                testing = false
                            }
                        }
                    }, enabled = !testing && model.isNotBlank() && baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), variant = SyButtonVariant.Outline)

            }
        }
    }
    pendingRemoveId?.let { id ->
        SyResponsiveSheet(title = stringResource(R.string.local_model_remove),
            subtitle = stringResource(R.string.local_model_remove_confirm),
            onDismiss = { pendingRemoveId = null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SyButton(stringResource(R.string.common_cancel), onClick = { pendingRemoveId = null },
                    modifier = Modifier.weight(1f), variant = SyButtonVariant.Ghost)
                SyButton(stringResource(R.string.local_model_remove), onClick = {
                    viewModel.removeLocalModel(id)
                    pendingRemoveId = null
                }, modifier = Modifier.weight(1f), variant = SyButtonVariant.Danger)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelCapabilityTags(capabilities: Set<LocalModelCapability>) {
    if (capabilities.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        capabilities.sortedBy { it.ordinal }.forEach { capability ->
            SyPill(text = stringResource(modelCapabilityLabel(capability)))
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
    val colors = LocalSyColors.current
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

    SettingsCard(stringResource(R.string.pricing_deepseek_title)) {
        Text(
            stringResource(R.string.pricing_current_period, periodLabel),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.text,
        )
        Text(sourceLabel, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
        Text(
            stringResource(R.string.pricing_source_official),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )

        state.models.forEach { model ->
            SyMaterialFrame(
                modifier = Modifier.fillMaxWidth(),
                tier = SyMaterialTier.L3_CARD,
                horizontalPadding = 0.dp,
                verticalPadding = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(model.displayName, style = DsType.std14Strong.withReadingWeight(), color = colors.text)
                    Text(
                        "${model.modelId} · ${model.version}",
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textTertiary,
                    )
                    Text(
                        stringResource(R.string.pricing_same_thinking),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                    // 表头：缓存命中 / 缓存未命中 / 输出
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1.5f))
                        Text(
                            stringResource(R.string.pricing_col_cache_hit),
                            style = DsType.caption11.withReadingWeight(), color = colors.textTertiary,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        )
                        Text(
                            stringResource(R.string.pricing_col_cache_miss),
                            style = DsType.caption11.withReadingWeight(), color = colors.textTertiary,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        )
                        Text(
                            stringResource(R.string.pricing_col_output),
                            style = DsType.caption11.withReadingWeight(), color = colors.textTertiary,
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
            color = colors.textTertiary,
        )
        state.error?.let { error ->
            Text(
                stringResource(R.string.pricing_refresh_failed, error),
                style = DsType.caption11.withReadingWeight(),
                color = colors.dangerInk,
            )
        }
        SyButton(
            text = stringResource(
                if (state.refreshing) R.string.pricing_refreshing else R.string.pricing_refresh,
            ),
            onClick = viewModel::refreshDeepSeekPricing,
            enabled = !state.refreshing,
            variant = SyButtonVariant.Outline,
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
    val colors = LocalSyColors.current
    val labelColor = if (active) colors.primary else colors.textSecondary
    val priceColor = if (active) colors.text else colors.textSecondary
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

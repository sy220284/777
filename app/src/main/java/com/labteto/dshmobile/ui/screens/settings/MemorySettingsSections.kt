package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric




import com.labteto.dshmobile.ui.shenyan.SySearchField

import com.labteto.dshmobile.ui.components.FeatherIcons
import com.shenyan.designsystem.components.SySwitchControl

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.memoryVaultFolders
import com.labteto.dshmobile.local.model.DeepSeekBillingSchedule
import com.labteto.dshmobile.local.model.DeepSeekPricePeriod
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelCapability
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyValueRow
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun MemoryOverviewCard(
    local: LocalHarnessSettingsState,
) {
    AppSettingsSection {
        AppSettingsRow(null, stringResource(R.string.advanced_auto_recall),
            value = stringResource(if (local.autoRecall) R.string.common_enabled else R.string.common_disabled))
        AppSettingsDivider()
        AppSettingsRow(null, stringResource(R.string.advanced_auto_memory),
            value = stringResource(if (local.autoMemory) R.string.common_enabled else R.string.common_disabled))
    }
}

private enum class MemoryFilter { ALL, RULE, PREFERENCE, FACT }

private fun MemoryKind.matchesFilter(filter: MemoryFilter): Boolean = when (filter) {
    MemoryFilter.ALL -> true
    MemoryFilter.RULE -> this == MemoryKind.RULE || this == MemoryKind.CONSTRAINT
    MemoryFilter.PREFERENCE ->
        this == MemoryKind.PREFERENCE || this == MemoryKind.RELATIONSHIP_PREFERENCE
    MemoryFilter.FACT -> this in setOf(
        MemoryKind.FACT,
        MemoryKind.DECISION,
        MemoryKind.STATE,
        MemoryKind.SUMMARY,
        MemoryKind.RELATIONSHIP_FACT,
        MemoryKind.RELATIONSHIP_STATE,
    )
}

@Composable
internal fun LocalMemorySettingsCard(
    local: LocalHarnessSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val memorySavedMessage = stringResource(R.string.advanced_memory_saved)
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val editor = viewModel.personalizationRulesEditor
    val rules by editor.state.collectAsState()
    // A completed disk write may outlive this page. Never emit a toast on a different page.
    val pageActive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { pageActive.set(false) } }
    val notifyCurrentPage: (Boolean, String?) -> Unit = { saved, error ->
        if (pageActive.get()) {
            if (saved) report(memorySavedMessage)
            else if (error != null) report(error)
        }
    }

    SettingsCard(stringResource(R.string.advanced_memory_settings)) {
        SyValueRow(
            label = stringResource(R.string.advanced_user_rules),
            value = stringResource(R.string.advanced_user_rules_count, local.userRules.length, 6_000),
            hint = stringResource(R.string.advanced_user_rules_hint),
            onClick = { editor.open(local.userRules) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.advanced_auto_recall), style = DsType.small13Strong.withReadingWeight(), color = colors.text)
                Text(stringResource(R.string.advanced_auto_recall_hint), style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
            }
            SySwitchControl(
                checked = local.autoRecall,
                onCheckedChange = { next ->
                    viewModel.changeLocalMemoryRecall(
                        if (rules.saving) rules.text else local.userRules,
                        local.autoRecall, local.autoMemory, next,
                        notifyCurrentPage,
                    )
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.advanced_auto_memory), style = DsType.small13Strong.withReadingWeight(), color = colors.text)
                Text(stringResource(R.string.advanced_auto_memory_hint), style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
            }
            SySwitchControl(
                checked = local.autoMemory,
                onCheckedChange = { next ->
                    viewModel.changeLocalMemoryAutoMemory(
                        if (rules.saving) rules.text else local.userRules,
                        local.autoRecall, local.autoMemory, next,
                        notifyCurrentPage,
                    )
                },
            )
        }
    }

    PersonalizationRulesSheet(
        rules = rules,
        onDismiss = editor::cancel,
        onDraftChange = editor::edit,
        onSave = {
            editor.save(
                submit = { text, complete ->
                    viewModel.configureLocalMemory(text, local.autoRecall, local.autoMemory, complete)
                },
                onSaved = { if (pageActive.get()) report(memorySavedMessage) },
            )
        },
    )
}

@Composable
internal fun PersonalizationRulesSheet(
    rules: PersonalizationRulesEditState,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    if (!rules.open) return
    SyResponsiveSheet(
        title = stringResource(R.string.advanced_user_rules),
        subtitle = stringResource(R.string.advanced_user_rules_hint),
        onDismiss = onDismiss,
        scrollable = true,
        footer = {
            SyButton(
                text = stringResource(R.string.common_save),
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag("personalizationRulesSave"),
                size = SyButtonSize.Large,
                enabled = !rules.saving,
                loading = rules.saving,
            )
        },
    ) {
        rules.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = DsType.caption11.withReadingWeight())
        }
        SyTextField(
            value = rules.text,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth().testTag("personalizationRulesInput"),
            minLines = 5,
            maxLines = 10,
            enabled = !rules.saving,
            label = { Text(stringResource(R.string.advanced_user_rules)) },
            supportingText = {
                Text(stringResource(R.string.advanced_user_rules_count, rules.text.length, 6_000))
            },
        )
    }
}

@Composable
internal fun MemoryManagementCard(
    page: MemoryManagementUiState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
    focusedSource: Boolean = false,
    onShowAll: () -> Unit = {},
) {
    val memoryUpdatedMessage = stringResource(R.string.advanced_memory_updated)
    val memoryDeactivatedMessage = stringResource(R.string.advanced_memory_deactivated)
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val records = page.records
    val query = page.query
    val filter = MemoryFilter.entries.firstOrNull { candidate ->
        MemoryKind.entries.filter { it.matchesFilter(candidate) }.toSet() == page.kinds
    } ?: MemoryFilter.ALL
    var vaultMode by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val filteredRecords = records
    val folders = remember(records) { memoryVaultFolders(records) }
    val visibleRecords = remember(records, folders, vaultMode) {
        if (vaultMode) folders.flatMap { it.records } else records
    }
    val firstFolderRecords = remember(folders) {
        folders.mapNotNull { folder -> folder.records.firstOrNull()?.id?.let { it to folder } }.toMap()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg)) {
        if (focusedSource) {
            Text(
                stringResource(R.string.advanced_memory_from_message),
                style = DsType.small13.withReadingWeight(), color = colors.textSecondary,
            )
            SyButton(
                text = stringResource(R.string.advanced_memory_show_all),
                onClick = onShowAll,
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (records.isEmpty() && query.isBlank() && filter == MemoryFilter.ALL && !page.loading && page.error == null) {
            Text(
                stringResource(
                    if (focusedSource) R.string.advanced_memory_no_message_source
                    else R.string.advanced_memory_empty,
                ),
                style = DsType.small13.withReadingWeight(),
                color = colors.textTertiary,
            )
            return@Column
        }

        Text(
            stringResource(R.string.advanced_memory_manage_hint),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )
        SySearchField(
            value = query,
            onValueChange = { text ->
                viewModel.searchMemories(text.take(200), MemoryKind.entries.filter { it.matchesFilter(filter) }.toSet())
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.advanced_memory_search),
        )

        SyButton(
            text = stringResource(
                if (vaultMode) R.string.advanced_memory_flat_view else R.string.advanced_memory_vault_view
            ),
            onClick = { vaultMode = !vaultMode },
            variant = SyButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )

        val filterOptions = listOf(
            MemoryFilter.ALL to R.string.advanced_memory_filter_all,
            MemoryFilter.RULE to R.string.advanced_kind_rule,
            MemoryFilter.PREFERENCE to R.string.advanced_kind_preference,
            MemoryFilter.FACT to R.string.advanced_kind_fact,
        )
        // Screen 24: persistent four-category track. All counts are derived from
        // MemoryRecord; the reference's six demo counters are not production data.
        SySegmented(
            segments = filterOptions.map { (candidate, label) ->
                SySegment(
                    candidate.name,
                    stringResource(
                        R.string.advanced_memory_filter_count,
                        stringResource(label),
                        page.kindCounts.filterKeys { it.matchesFilter(candidate) }.values.sum(),
                    ),
                )
            },
            selectedKey = filter.name,
            onSelect = { value ->
                val nextFilter = MemoryFilter.entries.firstOrNull { it.name == value } ?: MemoryFilter.ALL
                viewModel.searchMemories(query, MemoryKind.entries.filter { it.matchesFilter(nextFilter) }.toSet())
            },
            role = Role.Tab,
            hapticOnChange = true,
            stretch = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.advanced_memory_visible_count, records.size, page.totalMatching),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )

        if (visibleRecords.isEmpty()) {
            Text(
                stringResource(R.string.advanced_memory_filter_empty),
                style = DsType.small13.withReadingWeight(),
                color = colors.textTertiary,
            )
        }

        visibleRecords.forEach { record ->
            if (vaultMode) {
                firstFolderRecords[record.id]?.let { folder ->
                    Text(
                        listOfNotNull(
                            memoryScopeLabel(folder.scope),
                            folder.ownerId?.take(18),
                            memoryKindLabel(folder.kind),
                        ).joinToString(" / "),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(SyMetric.gapLg),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                    Text(
                        listOfNotNull(memoryKindLabel(record.kind), memoryScopeLabel(record.scope),
                            stringResource(R.string.advanced_pinned).takeIf { record.pinned }).joinToString(" · "),
                        style = DsType.small13.withReadingWeight(), color = colors.textTertiary,
                    )
                    Text(
                        record.content,
                        style = DsType.std14.withReadingWeight(),
                        color = colors.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                    ) {
                        Text(
                            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAt)),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textTertiary,
                            modifier = Modifier.weight(1f),
                        )
                        SyButton(
                            text = stringResource(R.string.advanced_edit_memory),
                            onClick = { editingId = record.id },
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                        SyButton(
                            text = stringResource(R.string.advanced_deactivate),
                            enabled = record.id !in page.busyIds,
                            onClick = {
                                viewModel.forgetMemory(record.id) { error ->
                                    report(error ?: memoryDeactivatedMessage)
                                }
                            },
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                    }
                }
            }
        }
        if (page.loading) Text(stringResource(R.string.common_loading), color = colors.textSecondary)
        page.error?.let { error ->
            Text(error, color = colors.dangerInk)
            SyButton(stringResource(R.string.common_retry), onClick = viewModel::refreshMemories)
        }
        if (page.next != null) {
            SyButton(
                text = stringResource(R.string.advanced_memory_show_more, 20),
                onClick = viewModel::loadOlderMemories,
                enabled = !page.loading,
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
        }
    }

    val editing = records.firstOrNull { it.id == editingId }
    if (editing != null) {
        var content by remember(editing.id) { mutableStateOf(editing.content) }
        var pinned by remember(editing.id) { mutableStateOf(editing.pinned) }
        var saving by remember(editing.id) { mutableStateOf(false) }
        var saveError by remember(editing.id) { mutableStateOf<String?>(null) }
        SyResponsiveSheet(
            title = stringResource(R.string.advanced_edit_memory),
            subtitle = memoryScopeLabel(editing.scope) + " · " + memoryKindLabel(editing.kind),
            onDismiss = { editingId = null },
            dismissEnabled = !saving,
            scrollable = true,
            footer = {
                saveError?.let { Text(it, style = DsType.small13.withReadingWeight(), color = colors.dangerInk) }
                SyButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        saving = true
                        saveError = null
                        viewModel.updateMemory(
                            id = editing.id,
                            content = content,
                            pinned = pinned,
                        ) { error ->
                            saving = false
                            saveError = error
                            if (error == null) editingId = null
                            report(error ?: memoryUpdatedMessage)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    size = SyButtonSize.Large,
                    enabled = !saving,
                    loading = saving,
                )
            },
        ) {
            SyTextField(
                value = content,
                onValueChange = { content = it.take(2_000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.advanced_memory_content)) },
                minLines = 4,
                maxLines = 8,
                enabled = !saving,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.advanced_pin), style = DsType.small13Strong.withReadingWeight(), color = colors.text)
                    Text(stringResource(R.string.advanced_pin_hint), style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
                }
                SySwitchControl(checked = pinned, onCheckedChange = { pinned = it }, enabled = !saving)
            }

        }
    }
}

@Composable
private fun memoryScopeLabel(scope: MemoryScope): String = stringResource(
    when (scope) {
        MemoryScope.GLOBAL -> R.string.advanced_scope_global
        MemoryScope.PROJECT -> R.string.advanced_scope_project
        MemoryScope.LINEAGE -> R.string.advanced_scope_lineage
    },
)

@Composable
private fun memoryKindLabel(kind: MemoryKind): String = stringResource(
    when (kind) {
        MemoryKind.RULE -> R.string.advanced_kind_rule
        MemoryKind.PREFERENCE -> R.string.advanced_kind_preference
        MemoryKind.FACT -> R.string.advanced_kind_fact
        MemoryKind.DECISION -> R.string.advanced_kind_decision
        MemoryKind.CONSTRAINT -> R.string.advanced_kind_constraint
        MemoryKind.STATE -> R.string.advanced_kind_state
        MemoryKind.SUMMARY -> R.string.advanced_kind_summary
        MemoryKind.RELATIONSHIP_FACT -> R.string.advanced_kind_fact
        MemoryKind.RELATIONSHIP_STATE -> R.string.advanced_kind_state
        MemoryKind.RELATIONSHIP_PREFERENCE -> R.string.advanced_kind_preference
    },
)

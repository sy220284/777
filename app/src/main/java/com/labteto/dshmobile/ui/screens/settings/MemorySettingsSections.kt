package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.model.DeepSeekBillingSchedule
import com.labteto.dshmobile.local.model.DeepSeekPricePeriod
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelCapability
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.DsValueRow
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.Ds
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
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
    recordCount: Int,
) {
    val colors = DsTheme.colors
    SettingsCard(stringResource(R.string.advanced_memory_overview), Icons.Outlined.Memory) {
        MemoryAuraPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(DsShapes.cube)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    colors.accent.copy(alpha = 0.34f),
                                    Ds.FamilyCyan.copy(alpha = 0.14f),
                                    Ds.FamilyPurple.copy(alpha = 0.06f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.advanced_memory_active_count, recordCount),
                        style = DsType.large20.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    Text(
                        stringResource(R.string.advanced_memory_manage_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                MemorySignalTile(
                    text = stringResource(
                        if (local.autoRecall) R.string.advanced_auto_recall_on
                        else R.string.advanced_auto_recall_off,
                    ),
                    enabled = local.autoRecall,
                    modifier = Modifier.weight(1f),
                )
                MemorySignalTile(
                    text = stringResource(
                        if (local.autoMemory) R.string.advanced_auto_memory_on
                        else R.string.advanced_auto_memory_off,
                    ),
                    enabled = local.autoMemory,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MemoryAuraPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val edge = Brush.linearGradient(
        listOf(
            colors.accent.copy(alpha = 0.78f),
            Ds.FamilyCyan.copy(alpha = 0.54f),
            Ds.FamilyPurple.copy(alpha = 0.50f),
            colors.accent.copy(alpha = 0.30f),
        ),
    )
    val wash = Brush.linearGradient(
        listOf(
            colors.accent.copy(alpha = 0.12f),
            Ds.FamilyPurple.copy(alpha = 0.07f),
            Color.Transparent,
        ),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(edge)
            .padding(1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.wallpaperSurface(WallpaperSurfaceLevel.CARD))
                .background(wash)
                .padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            content = content,
        )
    }
}

@Composable
private fun MemorySignalTile(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val tone = if (enabled) colors.accent else colors.labelCaption
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = DsShapes.row,
        color = tone.copy(alpha = if (enabled) 0.10f else 0.06f),
        border = BorderStroke(
            1.dp,
            if (enabled) tone.copy(alpha = 0.28f) else colors.borderL1,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(DsShapes.pillFull)
                    .background(tone),
            )
            Text(
                text,
                style = DsType.caption11Strong.withReadingWeight(),
                color = if (enabled) colors.labelPrimary else colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemoryToggleRow(
    icon: ImageVector,
    family: DsIconFamily,
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.row,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING),
        border = BorderStroke(
            1.dp,
            if (checked) colors.accent.copy(alpha = 0.22f) else colors.borderL1,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsIconBox(
                icon = icon,
                family = family,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    hint,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun MemoryRecordSurface(
    kind: MemoryKind,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val tone = when (kind) {
        MemoryKind.RULE,
        MemoryKind.CONSTRAINT -> colors.accent

        MemoryKind.PREFERENCE,
        MemoryKind.RELATIONSHIP_PREFERENCE -> Ds.FamilyPurple

        MemoryKind.RELATIONSHIP_FACT,
        MemoryKind.RELATIONSHIP_STATE -> Ds.FamilyCyan

        MemoryKind.DECISION -> colors.warn
        else -> colors.labelTertiary
    }
    val edge = Brush.linearGradient(
        listOf(
            tone.copy(alpha = 0.58f),
            tone.copy(alpha = 0.18f),
            colors.borderL1,
        ),
    )
    val wash = Brush.horizontalGradient(
        listOf(
            tone.copy(alpha = 0.09f),
            Color.Transparent,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(edge)
            .padding(1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.wallpaperSurface(WallpaperSurfaceLevel.CARD))
                .background(wash)
                .padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            content = content,
        )
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
    val colors = DsTheme.colors
    var userRules by remember(local.userRules) { mutableStateOf(local.userRules) }
    var showRulesEditor by remember { mutableStateOf(false) }

    SettingsCard(stringResource(R.string.advanced_memory_settings), Icons.Outlined.Memory) {
        DsValueRow(
            label = stringResource(R.string.advanced_user_rules),
            value = stringResource(R.string.advanced_user_rules_count, userRules.length, 6_000),
            hint = stringResource(R.string.advanced_user_rules_hint),
            onClick = { showRulesEditor = true },
        )
        MemoryToggleRow(
            icon = Icons.Outlined.Memory,
            family = DsIconFamily.Cyan,
            title = stringResource(R.string.advanced_auto_recall),
            hint = stringResource(R.string.advanced_auto_recall_hint),
            checked = local.autoRecall,
            onCheckedChange = { next ->
                viewModel.configureLocalMemory(userRules, next, local.autoMemory)
                report(memorySavedMessage)
            },
        )
        MemoryToggleRow(
            icon = Icons.Outlined.Tune,
            family = DsIconFamily.Purple,
            title = stringResource(R.string.advanced_auto_memory),
            hint = stringResource(R.string.advanced_auto_memory_hint),
            checked = local.autoMemory,
            onCheckedChange = { next ->
                viewModel.configureLocalMemory(userRules, local.autoRecall, next)
                report(memorySavedMessage)
            },
        )
    }

    if (showRulesEditor) {
        DsBottomSheet(
            title = stringResource(R.string.advanced_user_rules),
            subtitle = stringResource(R.string.advanced_user_rules_hint),
            onDismiss = { showRulesEditor = false },
        ) {
            OutlinedTextField(
                value = userRules,
                onValueChange = { userRules = it.take(6_000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
                label = { Text(stringResource(R.string.advanced_user_rules)) },
                supportingText = {
                    Text(stringResource(R.string.advanced_user_rules_count, userRules.length, 6_000))
                },
            )
            DsButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    viewModel.configureLocalMemory(userRules, local.autoRecall, local.autoMemory)
                    report(memorySavedMessage)
                    showRulesEditor = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun MemoryManagementCard(
    records: List<MemoryRecord>,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val memoryUpdatedMessage = stringResource(R.string.advanced_memory_updated)
    val memoryDeactivatedMessage = stringResource(R.string.advanced_memory_deactivated)
    val colors = DsTheme.colors
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(MemoryFilter.ALL) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var visibleLimit by remember(records, query, filter) { mutableStateOf(20) }

    val filteredRecords = remember(records, query, filter) {
        val needle = query.trim()
        records.filter { record ->
            record.kind.matchesFilter(filter) &&
                (needle.isBlank() || record.content.contains(needle, ignoreCase = true))
        }
    }
    val visibleRecords = remember(filteredRecords, visibleLimit) {
        filteredRecords.take(visibleLimit)
    }

    SettingsCard(stringResource(R.string.advanced_manage_memory), Icons.Outlined.Memory) {
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.advanced_memory_empty),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelTertiary,
            )
            return@SettingsCard
        }

        Text(
            stringResource(R.string.advanced_memory_manage_hint),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(200) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.advanced_memory_search)) },
        )

        val filterOptions = listOf(
            MemoryFilter.ALL to R.string.advanced_memory_filter_all,
            MemoryFilter.RULE to R.string.advanced_kind_rule,
            MemoryFilter.PREFERENCE to R.string.advanced_kind_preference,
            MemoryFilter.FACT to R.string.advanced_kind_fact,
        )
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            filterOptions.chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    rowOptions.forEach { (candidate, label) ->
                        val count = records.count { it.kind.matchesFilter(candidate) }
                        DsPill(
                            text = stringResource(
                                R.string.advanced_memory_filter_count,
                                stringResource(label),
                                count,
                            ),
                            selected = filter == candidate,
                            onClick = { filter = candidate },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Text(
            stringResource(R.string.advanced_memory_visible_count, filteredRecords.size, records.size),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )

        if (visibleRecords.isEmpty()) {
            Text(
                stringResource(R.string.advanced_memory_filter_empty),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelTertiary,
            )
        }

        visibleRecords.forEach { record ->
            MemoryRecordSurface(record.kind) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsPill(text = memoryKindLabel(record.kind), selected = true)
                    DsPill(text = memoryScopeLabel(record.scope))
                    if (record.pinned) {
                        DsPill(text = stringResource(R.string.advanced_pinned), warn = true)
                    }
                }
                Text(
                    record.content,
                    style = DsType.std14.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAt)),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelCaption,
                        modifier = Modifier.weight(1f),
                    )
                    DsButton(
                        text = stringResource(R.string.advanced_edit_memory),
                        onClick = { editingId = record.id },
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                    DsButton(
                        text = stringResource(R.string.advanced_deactivate),
                        onClick = {
                            viewModel.forgetMemory(record.id) { error ->
                                report(error ?: memoryDeactivatedMessage)
                            }
                        },
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
            }
        }
        if (visibleRecords.size < filteredRecords.size) {
            val remaining = filteredRecords.size - visibleRecords.size
            DsButton(
                text = stringResource(
                    R.string.advanced_memory_show_more,
                    minOf(20, remaining),
                ),
                onClick = {
                    visibleLimit = (visibleLimit + 20).coerceAtMost(filteredRecords.size)
                },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }

    val editing = records.firstOrNull { it.id == editingId }
    if (editing != null) {
        var content by remember(editing.id, editing.updatedAt) { mutableStateOf(editing.content) }
        var pinned by remember(editing.id, editing.updatedAt) { mutableStateOf(editing.pinned) }
        DsBottomSheet(
            title = stringResource(R.string.advanced_edit_memory),
            subtitle = memoryScopeLabel(editing.scope) + " · " + memoryKindLabel(editing.kind),
            onDismiss = { editingId = null },
        ) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(2_000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.advanced_memory_content)) },
                minLines = 4,
                maxLines = 8,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.advanced_pin), style = DsType.small13Strong.withReadingWeight(), color = colors.labelPrimary)
                    Text(stringResource(R.string.advanced_pin_hint), style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                }
                Switch(checked = pinned, onCheckedChange = { pinned = it })
            }
            DsButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    viewModel.updateMemory(
                        id = editing.id,
                        content = content,
                        pinned = pinned,
                    ) { error ->
                        report(error ?: memoryUpdatedMessage)
                    }
                    editingId = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
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

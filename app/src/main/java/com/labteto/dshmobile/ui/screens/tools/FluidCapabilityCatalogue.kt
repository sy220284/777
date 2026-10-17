package com.labteto.dshmobile.ui.screens.tools

import com.shenyan.designsystem.foundation.SyMetric

import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SySearchField
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.shenyan.designsystem.components.SySheetChoiceRow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiToolCapabilitySnapshot
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*

/** Design 14: real discovery groups, compact cache summary and a detail rail as a Sheet. */
@Composable
internal fun FluidCapabilityCatalogue(entries: List<UiToolCapabilitySnapshot>, loading: Boolean, failed: Boolean,
    connections: Int, onRefresh: () -> Unit, onBack: () -> Unit, onManage: () -> Unit,
    onPlugins: () -> Unit, onSkills: () -> Unit, onUseCapability: ((String) -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<UiToolCapabilitySnapshot?>(null) }
    val families = entries.groupBy { it.family }.toSortedMap()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        SyTopBar(stringResource(R.string.tools_title), onBack = onBack,
            backContentDescription = stringResource(R.string.common_back),
            actionIcon = FeatherIcons.RefreshCw, onAction = onRefresh, actionEnabled = !loading,
            actionContentDescription = stringResource(R.string.tools_refresh))
        SySearchField(query, { query = it }, placeholder = stringResource(R.string.tools_catalog_search),
            modifier = Modifier.padding(horizontal = SyMetric.gapLg))
        Row(Modifier.fillMaxWidth().padding(SyMetric.gapLg),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            SyButton(stringResource(R.string.tools_catalog_title), onPlugins,
                variant = SyButtonVariant.Ghost, modifier = Modifier.weight(1f))
            SyButton(stringResource(R.string.skills_title), onSkills,
                variant = SyButtonVariant.Ghost, modifier = Modifier.weight(1f))
            SyButton(stringResource(R.string.fluid_capability_manage), onManage,
                variant = SyButtonVariant.Ghost, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg)) {
            item {
                SyGlassCard {
                    Text(stringResource(R.string.fluid_capability_count, entries.size, families.size, connections),
                        style = DsType.std14Strong, color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
                    Text(stringResource(R.string.fluid_capability_hint), style = DsType.small13,
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
                    if (loading) Text(stringResource(R.string.common_loading), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
                    if (failed) {
                        Text(stringResource(R.string.tools_load_failed), color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk)
                        SyButton(stringResource(R.string.common_retry), onRefresh, enabled = !loading)
                    }
                }
            }
            if (!loading && entries.isEmpty()) item {
                Text(stringResource(R.string.fluid_capability_none), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
            families.forEach { (family, tools) ->
                val visible = tools.filter { query.isBlank() || it.name.contains(query, true) || family.contains(query, true) }
                if (visible.isNotEmpty()) {
                    item(key = "heading:$family") {
                        Text(family, style = DsType.caption11Strong, color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary)
                    }
                    items(visible, key = { it.name }) { entry ->
                        SySheetChoiceRow(title = entry.name,
                            modifier = Modifier.heightIn(min = 72.dp),
                            icon = com.labteto.dshmobile.ui.components.SyEntryIcons.Tools, onClick = { selected = entry })
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        val usePrompt = stringResource(R.string.plugin_use_prompt, entry.name, entry.name)
        SyResponsiveSheet(title = entry.name, onDismiss = { selected = null }, scrollable = true) {
            Text(entry.family, style = DsType.std14Strong, color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
            Text(stringResource(R.string.fluid_capability_hint), style = DsType.small13,
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            if (entry.requirements.isNotEmpty()) {
                Text(stringResource(R.string.fluid_capability_requirements), style = DsType.std14Strong)
                entry.requirements.forEach { Text(it, style = DsType.small13) }
            }
            Text(entry.access + " · " + entry.approval, style = DsType.caption11,
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary)
            onUseCapability?.let { use -> SyButton(stringResource(R.string.plugin_use), {
                selected = null; use(usePrompt)
            }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

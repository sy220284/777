package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SySearchField
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.foundation.LocalSyColors

internal data class SettingsSearchFocus(val title: String, val request: String)
internal val LocalSettingsSearchFocus = compositionLocalOf<SettingsSearchFocus?> { null }

/** Attached by the actual setting row/heading, so search cannot drift to a stale offset. */
internal fun Modifier.settingsSearchAnchor(title: String): Modifier = composed {
    val target = LocalSettingsSearchFocus.current
    if (target?.title != title) return@composed this
    val requester = remember { BringIntoViewRequester() }
    var placed by remember(target.request) { mutableStateOf(false) }
    val description = stringResource(R.string.settings_search_focus)
    LaunchedEffect(target.request, placed) {
        if (placed) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    this.bringIntoViewRequester(requester)
        .onGloballyPositioned { placed = true }
        .background(LocalSyColors.current.primary.copy(alpha = .14f))
        .testTag("settings-search-target")
        .semantics { stateDescription = description }
}

@Composable
internal fun SettingsSearchResults(
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SettingsSearchEntry) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val colors = LocalSyColors.current
    val results = remember(query, context.resources.configuration) {
        searchSettingsEntries(query, title = { context.getString(it) })
    }
    Column(Modifier.fillMaxSize()) {
        SySearchField(query, onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag("settings-search-input"),
            placeholder = stringResource(R.string.settings_search_hint))
        Text(stringResource(R.string.settings_search_count, results.size),
            style = DsType.small13, color = colors.textSecondary)
        if (results.isEmpty()) Text(stringResource(R.string.settings_search_empty), color = colors.textSecondary)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("settings-search-results")) {
            items(results, key = { it.id }) { entry ->
                val page = settingsPageEntry(entry.destination)
                AppSettingsRow(
                    icon = FeatherIcons.Search,
                    title = stringResource(entry.titleRes),
                    subtitle = page?.let { stringResource(R.string.settings_search_path, stringResource(it.titleRes)) },
                    onClick = { focus.clearFocus(); onSelect(entry) },
                    compact = true,
                )
            }
        }
    }
}

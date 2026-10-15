package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.tools.LocalComposerInvocationOption
import com.labteto.dshmobile.local.tools.LocalPinnedInvocation
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/**
 * Small opt-in surface inside the existing V6.3 composer.
 * Both connectors and skills are real installed entries; no simulated marketplace rows.
 */
@Composable
internal fun LocalComposerInvocationPicker(
    options: List<LocalComposerInvocationOption>,
    pinned: List<LocalPinnedInvocation>,
    onToggle: (LocalPinnedInvocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSyColors.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val entries = options.filter { option ->
        query.isBlank() || option.label.contains(query, ignoreCase = true) ||
            option.invocation.id.contains(query, ignoreCase = true)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
    ) {
        Box {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(40.dp)) {
                Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.composer_invocation_add),
                    tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
            SyPopupMenu(
                expanded = expanded,
                onDismiss = { expanded = false; query = "" },
                modifier = Modifier.width(310.dp),
            ) {
                Text(stringResource(R.string.composer_invocation_title), color = colors.text,
                    style = DsType.std14Strong.withReadingWeight(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.composer_invocation_search)) },
                    singleLine = true,
                    modifier = Modifier.width(286.dp).padding(horizontal = 8.dp),
                )
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.composer_invocation_empty), color = colors.textSecondary,
                        style = DsType.small13.withReadingWeight(),
                        modifier = Modifier.padding(16.dp))
                }
                for (kind in listOf(LocalPinnedInvocation.Kind.CONNECTOR, LocalPinnedInvocation.Kind.SKILL)) {
                    val section = entries.filter { it.invocation.kind == kind }
                    if (section.isNotEmpty()) {
                        Text(if (kind == LocalPinnedInvocation.Kind.CONNECTOR) stringResource(R.string.composer_invocation_connected) else stringResource(R.string.composer_invocation_installed),
                            color = colors.textSecondary, style = DsType.small13.withReadingWeight(),
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        section.forEach { option ->
                            val checked = option.invocation in pinned
                            DropdownMenuItem(
                                text = {
                                    androidx.compose.foundation.layout.Column {
                                        Text(option.label, color = if (option.available) colors.text else colors.textTertiary,
                                            style = DsType.small13.withReadingWeight())
                                        Text(option.detail.take(64), color = colors.textSecondary,
                                            style = DsType.caption11.withReadingWeight(), maxLines = 1)
                                    }
                                },
                                leadingIcon = {
                                    LocalInvocationIcon(option.invocation, Modifier.size(20.dp))
                                },
                                trailingIcon = {
                                    if (checked) Icon(FeatherIcons.CheckSquare, null,
                                        tint = colors.primary, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    if (option.available || checked) onToggle(option.invocation)
                                },
                                enabled = option.available || checked,
                            )
                        }
                    }
                }
                Text(stringResource(R.string.composer_invocation_hint),
                    style = DsType.caption11.withReadingWeight(), color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        if (pinned.isEmpty()) {
            Text(stringResource(R.string.composer_invocation_select), color = colors.textSecondary,
                style = DsType.caption11.withReadingWeight(),
                modifier = Modifier.weight(1f))
        } else {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                items(pinned, key = { it.encode() }) { item ->
                    val option = options.firstOrNull { it.invocation == item }
                    val name = option?.label ?: item.id
                    Surface(
                        onClick = { onToggle(item) },
                        color = if (option?.available == false) colors.selected else colors.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier.semantics { contentDescription = stringResource(R.string.composer_invocation_remove, name) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            LocalInvocationIcon(item, Modifier.size(15.dp))
                            Text(name, style = DsType.small13.withReadingWeight(),
                                color = colors.text, maxLines = 1)
                            Icon(FeatherIcons.X, contentDescription = null,
                                tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalInvocationIcon(
    item: LocalPinnedInvocation,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSyColors.current
    when {
        item.kind == LocalPinnedInvocation.Kind.SKILL ->
            Icon(FeatherIcons.BookOpen, null, modifier = modifier, tint = colors.primary)
        item.id.equals("github", ignoreCase = true) ->
            Icon(painterResource(R.drawable.ic_connector_github), null,
                modifier = modifier, tint = colors.text)
        item.id.contains("figma", ignoreCase = true) ->
            Icon(painterResource(R.drawable.ic_connector_figma), null,
                modifier = modifier, tint = Color.Unspecified)
        else ->
            Icon(FeatherIcons.Globe, null, modifier = modifier, tint = colors.primary)
    }
}

package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.ComposerInvocationUiPort
import com.labteto.dshmobile.uicontract.UiInvocationOption
import com.labteto.dshmobile.uicontract.UiInvocationKind
import com.labteto.dshmobile.uicontract.UiInvocationRef

/** Independent Next-pack Material rendering; all mutations go through the neutral Owner port. */
@Composable
internal fun NextInvocationPicker(
    port: ComposerInvocationUiPort,
    options: List<UiInvocationOption>,
    selected: List<UiInvocationRef>,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Text(stringResource(R.string.next_invocations_select))
        }
        selected.forEach { item ->
            val label = options.firstOrNull { it.ref == item }?.label ?: item.id
            FilterChip(selected = true, onClick = { port.toggle(item) },
                label = { Text(label + " ×") },
                leadingIcon = { NextInvocationIcon(item) })
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false; query = "" },
        title = { Text(stringResource(R.string.next_invocations_select)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.next_invocations_search)) },
                )
                val visible = options.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                        it.ref.id.contains(query, ignoreCase = true)
                }
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (visible.isEmpty()) Text(stringResource(R.string.next_invocations_empty))
                    visible.forEach { entry ->
                        val checked = entry.ref in selected
                        FilterChip(
                            selected = checked,
                            enabled = checked || entry.available,
                            onClick = { port.toggle(entry.ref) },
                            leadingIcon = { NextInvocationIcon(entry.ref) },
                            label = {
                                Column {
                                    Text(entry.label + (if (entry.available) "" else " · 离线"))
                                    Text(entry.description, maxLines = 1)
                                }
                            },
                        )
                    }
                }
                Text(stringResource(R.string.next_invocations_hint))
            }
        },
        confirmButton = { TextButton(onClick = { open = false; query = "" }) {
            Text(stringResource(R.string.next_close))
        } },
    )
}

@Composable
private fun NextInvocationIcon(item: UiInvocationRef) {
    val painter = when {
        item.kind == UiInvocationKind.SKILL -> null
        item.id.equals("github", ignoreCase = true) -> R.drawable.ic_connector_github
        item.id.contains("figma", ignoreCase = true) -> R.drawable.ic_connector_figma
        item.id.equals("sites", ignoreCase = true) || item.id.startsWith("sites-", ignoreCase = true) ->
            R.drawable.ic_connector_sites
        else -> null
    }
    if (painter != null) {
        Icon(painterResource(painter), contentDescription = null, modifier = Modifier.size(18.dp),
            tint = if (item.id.equals("github", ignoreCase = true)) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else Color.Unspecified)
    } else {
        Icon(if (item.kind == UiInvocationKind.SKILL) Icons.Outlined.AutoStories
            else Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

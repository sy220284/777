package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.ComposerInvocationUiPort
import com.labteto.dshmobile.uicontract.UiInvocationOption
import com.labteto.dshmobile.uicontract.UiInvocationKind
import com.labteto.dshmobile.uicontract.UiInvocationRef

/** Independent Next-pack Material rendering; all mutations go through the Owner port. */
@Composable
internal fun NextInvocationPicker(
    port: ComposerInvocationUiPort,
    options: List<UiInvocationOption>,
    selected: List<UiInvocationRef>,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Text(stringResource(R.string.next_invocations_select))
        }
        selected.forEach { item ->
            val label = options.firstOrNull { it.ref == item }?.label ?: item.id
            FilterChip(selected = true, onClick = { port.toggle(item) },
                label = { Text(label + " ×") })
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.next_invocations_select)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (options.isEmpty()) Text(stringResource(R.string.next_invocations_empty))
                options.forEach { entry ->
                    val checked = entry.ref in selected
                    FilterChip(
                        selected = checked,
                        enabled = checked || entry.available,
                        onClick = { port.toggle(entry.ref) },
                        label = {
                            Text((if (entry.ref.kind == UiInvocationKind.SKILL) "◆ " else "◎ ") +
                                entry.label + (if (entry.available) "" else " · 离线"))
                        },
                    )
                }
                Text(stringResource(R.string.next_invocations_hint))
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.next_close)) } },
    )
}

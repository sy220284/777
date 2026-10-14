package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.ChatGroupUiCommand
import com.labteto.dshmobile.uicontract.ChatGroupUiPort
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity

/** The Next pack selects only owner-supplied gallery identities and sends a closed intent. */
@Composable
internal fun NextChatGroupManager(
    groups: ChatGroupUiPort,
    identity: UiSessionIdentity,
    onReceipt: (UiCommandReceipt) -> Unit,
) {
    val snapshot by groups.snapshots.collectAsStateWithLifecycle(initialValue = groups.snapshot())
    if (snapshot.identity != identity) return

    var open by remember(identity) { mutableStateOf(false) }
    var creating by remember(identity) { mutableStateOf(false) }
    var selected by remember(identity) { mutableStateOf(emptySet<String>()) }
    TextButton(onClick = {
        val current = groups.snapshot()
        if (current.identity == identity && current.active) {
            creating = false
            selected = current.memberIds
            open = true
        }
    }, enabled = snapshot.active && !snapshot.busy) { Text(stringResource(R.string.next_group_manage)) }
    TextButton(onClick = {
        creating = true
        selected = emptySet()
        open = true
    }, enabled = !snapshot.busy) { Text(stringResource(R.string.next_group_create)) }
    TextButton(onClick = {
        onReceipt(groups.submit(ChatGroupUiCommand.SetGroupMode(identity, !snapshot.active)))
    }, enabled = !snapshot.busy) { Text(stringResource(if (snapshot.active) R.string.next_group_exit else R.string.next_group_enter)) }

    if (open) {
        val current = snapshot
        if (current.identity == identity && (creating || current.active)) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(if (creating) R.string.next_group_create else R.string.next_group_members_dialog)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(current.available, key = { it.galleryId }) { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = member.galleryId in selected,
                                onCheckedChange = { enabled ->
                                    selected = if (enabled) selected + member.galleryId
                                    else selected - member.galleryId
                                },
                            )
                            Text(member.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !current.busy && selected.size in current.minimumMembers..current.maximumMembers,
                    onClick = {
                        val command = if (creating) ChatGroupUiCommand.CreateGroupSession(identity, selected.toList())
                            else ChatGroupUiCommand.ConfigureMembers(identity, selected.toList())
                        val result = groups.submit(command)
                        onReceipt(result)
                        if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) open = false
                    },
                ) { Text(stringResource(R.string.next_group_save)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.next_cancel))
                }
            },
        )
        }
    }
}

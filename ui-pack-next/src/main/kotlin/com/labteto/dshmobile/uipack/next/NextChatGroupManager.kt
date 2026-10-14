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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    var announcementOpen by remember(identity) { mutableStateOf(false) }
    var announcementDraft by remember(identity) { mutableStateOf("") }
    var operationError by remember(identity) { mutableStateOf<String?>(null) }
    val writeFailed = stringResource(R.string.next_group_write_failed)
    var pending by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submitWrite(command: ChatGroupUiCommand, completed: (UiCommandReceipt) -> Unit = {}) {
        if (pending) return
        pending = true
        operationError = null
        scope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { groups.submit(command) }
                if (groups.snapshot().identity == identity) {
                    operationError = when (result) {
                        is UiCommandReceipt.Failed -> result.message
                        is UiCommandReceipt.Rejected, UiCommandReceipt.NotAdmitted -> writeFailed
                        else -> null
                    }
                    onReceipt(result); completed(result)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (groups.snapshot().identity == identity)
                { operationError = failure.message ?: writeFailed; onReceipt(UiCommandReceipt.Failed(requireNotNull(operationError))) } }
            finally { pending = false }
        }
    }
    TextButton(onClick = {
        val current = groups.snapshot()
        if (current.identity == identity && current.active) {
            creating = false
            selected = current.memberIds
            open = true
        }
    }, enabled = snapshot.active && !snapshot.busy && !pending) { Text(stringResource(R.string.next_group_manage)) }
    TextButton(onClick = {
        creating = true
        selected = emptySet()
        open = true
    }, enabled = !snapshot.busy && !pending) { Text(stringResource(R.string.next_group_create)) }
    TextButton(onClick = {
        onReceipt(groups.submit(ChatGroupUiCommand.SetGroupMode(identity, !snapshot.active)))
    }, enabled = !snapshot.busy && !pending) { Text(stringResource(if (snapshot.active) R.string.next_group_exit else R.string.next_group_enter)) }

    TextButton(onClick = {
        val current = groups.snapshot()
        if (current.identity == identity && current.active) {
            announcementDraft = current.announcement
            operationError = null
            announcementOpen = true
        }
    }, enabled = snapshot.active && !snapshot.busy && !pending) { Text(stringResource(R.string.next_group_announcement)) }
    if (announcementOpen && snapshot.active) AlertDialog(
        onDismissRequest = { if (!pending) announcementOpen = false },
        title = { Text(stringResource(R.string.next_group_announcement)) },
        text = { Column {
            OutlinedTextField(announcementDraft, { announcementDraft = it.take(snapshot.maximumAnnouncementChars) }, enabled = !pending,
                label = { Text(stringResource(R.string.next_group_announcement_content, snapshot.maximumAnnouncementChars)) }, minLines = 3, maxLines = 8)
            operationError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            submitWrite(ChatGroupUiCommand.SaveAnnouncement(identity, announcementDraft)) { result ->
                if (result == UiCommandReceipt.Accepted) announcementOpen = false
            }
        }, enabled = !snapshot.busy && !pending) { Text(stringResource(R.string.next_group_announcement_save)) } },
        dismissButton = { TextButton(onClick = { announcementOpen = false }, enabled = !pending) { Text(stringResource(R.string.next_cancel)) } },
    )

    if (open) {
        val current = snapshot
        if (current.identity == identity && (creating || current.active)) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(if (creating) R.string.next_group_create else R.string.next_group_members_dialog)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    operationError?.let { message -> item { Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error) } }
                    items(current.available, key = { it.galleryId }) { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = member.galleryId in selected,
                                onCheckedChange = { enabled ->
                                    selected = if (enabled) selected + member.galleryId
                                    else selected - member.galleryId
                                },
                            )
                            Column {
                                Text(member.displayName)
                                if (!creating && member.galleryId in current.memberIds) TextButton(onClick = {
                                    submitWrite(ChatGroupUiCommand.RemoveMember(identity, member.galleryId)) { result ->
                                        if (result == UiCommandReceipt.Accepted) selected = selected - member.galleryId
                                    }
                                }, enabled = !current.busy && !pending) { Text(stringResource(R.string.next_group_remove)) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !current.busy && !pending && selected.size in current.minimumMembers..current.maximumMembers,
                    onClick = {
                        val command = if (creating) ChatGroupUiCommand.CreateGroupSession(identity, selected.toList())
                            else ChatGroupUiCommand.ConfigureMembers(identity, selected.toList())
                        submitWrite(command) { result ->
                            if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) open = false
                        }
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

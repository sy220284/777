package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Actual child Job identity accompanies every member command. No V6 screen is called. */
@Composable
internal fun NextWorkTeamPanel(port: WorkTeamUiPort, identity: UiSessionIdentity) {
    val snapshot by port.snapshots.collectAsStateWithLifecycle(initialValue = port.snapshot())
    var open by remember(identity) { mutableStateOf(false) }
    var selected by remember(identity) { mutableStateOf<UiWorkTeamMember?>(null) }
    var message by remember(identity) { mutableStateOf("") }
    var response by remember(identity) { mutableStateOf<String?>(null) }
    var pending by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun send(command: WorkTeamUiCommand) {
        if (pending) return
        pending = true
        scope.launch {
            try {
                val result = port.submit(command)
                if (port.snapshot().identity == identity) response = result.message
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (port.snapshot().identity == identity) response = failure.message }
            finally { pending = false }
        }
    }
    TextButton(onClick = { open = true }, enabled = snapshot.identity == identity) { Text(stringResource(R.string.next_team)) }
    if (open && snapshot.identity == identity) AlertDialog(
        onDismissRequest = { open = false }, title = { Text(stringResource(R.string.next_team)) },
        text = { Column {
            if (snapshot.rebuilding) Text(stringResource(R.string.next_loading))
            snapshot.failure?.let { Text(it) }
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(snapshot.members, key = { it.jobId }) { member -> Column {
                    Text("${member.name} · ${member.phase} · ${member.activity}")
                    member.task?.let { Text(it) }
                    member.error?.let { Text(it) }
                    TextButton(onClick = { selected = member; message = "" }, enabled = !pending && member.phase == "active") {
                        Text(stringResource(R.string.next_team_message))
                    }
                    TextButton(onClick = { send(WorkTeamUiCommand.StopMember(identity, member.id, member.jobId)) }, enabled = !pending && member.phase == "active") {
                        Text(stringResource(R.string.next_team_stop_member))
                    }
                    TextButton(onClick = { response = port.readOutput(identity, member.id, member.jobId).message }) {
                        Text(stringResource(R.string.next_team_output))
                    }
                } }
                response?.let { item { Text(it) } }
            }
            selected?.let { member ->
                Text(member.name)
                OutlinedTextField(message, { message = it }, enabled = !pending, label = { Text(stringResource(R.string.next_input)) })
                TextButton(onClick = { send(WorkTeamUiCommand.SendMessage(identity, member.id, member.jobId, message)) }, enabled = !pending && message.isNotBlank()) {
                    Text(stringResource(R.string.next_team_message))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.next_close)) } },
        dismissButton = { TextButton(onClick = { send(WorkTeamUiCommand.StopTeam(identity, snapshot.members.map { it.jobId }.toSet())) }, enabled = !pending) {
            Text(stringResource(R.string.next_team_stop_all))
        } },
    )
}

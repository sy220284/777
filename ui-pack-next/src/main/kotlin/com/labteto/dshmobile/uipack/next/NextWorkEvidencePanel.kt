package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** File versions and origin validity are returned by Work, never inferred from model text. */
@Composable
internal fun NextWorkEvidencePanel(port: WorkEvidenceUiPort, identity: UiSessionIdentity, history: WorkReadUiPort? = null) {
    var generation by remember(identity) { mutableStateOf(0L) }
    var open by remember(identity) { mutableStateOf(false) }
    var snapshot by remember(identity) { mutableStateOf<UiWorkEvidenceSnapshot?>(null) }
    var selected by remember(identity) { mutableStateOf<UiWorkRequirement?>(null) }
    var inspected by remember(identity) { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem?>(null) }
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    var pending by remember(identity) { mutableStateOf(false) }
    var historyPage by remember(identity) { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi?>(null) }
    var historyLoaded by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun loadHistory() {
        val reader = history ?: return
        if (pending || historyLoaded && historyPage?.older == null) return
        val cursor = if (historyLoaded) historyPage?.older else null
        val request = ++generation
        pending = true
        scope.launch {
            try {
                val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    reader.historyPageForUi(identity.sessionId, cursor)
                }
                if (request != generation) return@launch
                historyPage = page
                historyLoaded = !page.invalidated
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (request == generation) feedback = failure.message }
            finally { if (request == generation) pending = false }
        }
    }
    val unavailable = stringResource(R.string.next_evidence_unavailable)
    val linked = stringResource(R.string.next_evidence_linked)
    fun refresh() {
        if (pending) return
        val request = ++generation
        pending = true
        scope.launch {
            try {
                val result = port.read(identity)
                if (request != generation) return@launch
                when (result) {
                    is UiWorkEvidenceReadReceipt.Available -> {
                        snapshot = result.snapshot; selected = null; inspected = null; historyPage = null; historyLoaded = false; open = true
                    }
                    is UiWorkEvidenceReadReceipt.Failed -> feedback = result.message ?: unavailable
                    is UiWorkEvidenceReadReceipt.Rejected -> feedback = unavailable
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (request == generation) feedback = failure.message ?: unavailable }
            finally { if (request == generation) pending = false }
        }
    }
    fun close() { generation++; open = false; pending = false; selected = null; inspected = null }
    TextButton(onClick = { open = true; refresh() }, enabled = !pending) { Text(stringResource(R.string.next_evidence)) }
    if (open) AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.next_evidence)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text(stringResource(R.string.next_evidence_not_verified)) }
            items(snapshot?.requirements.orEmpty(), key = { it.index }) { requirement ->
                TextButton(onClick = { selected = requirement; inspected = null }, enabled = !pending) { Text("${requirement.text} · ${requirement.reportedStatus}") }
                snapshot?.references?.filter { it.requirementIndex == requirement.index && it.requirement == requirement.text }?.forEach { reference ->
                    Text(reference.artifactPath)
                    Text(stringResource(when {
                        reference.versionNow == null -> R.string.next_artifact_unavailable
                        reference.versionNow != reference.versionAtLink -> R.string.next_artifact_changed
                        else -> R.string.next_evidence_source_current
                    }))
                }
            }
            selected?.let { requirement ->
                item { Text(requirement.text) }
                item {
                    if (history != null && (!historyLoaded || historyPage?.older != null)) TextButton(
                        onClick = { loadHistory() }, enabled = !pending,
                    ) { Text(stringResource(R.string.next_load_older)) }
                    if (historyPage?.invalidated == true) Text(stringResource(R.string.next_work_history_expired))
                }
                items((snapshot?.artifacts.orEmpty() + historyPage?.records.orEmpty().flatMap { it.artifacts })
                    .filter { it.category == "file" }.distinctBy { it.asOfSequence to it.reference }, key = { "${it.asOfSequence}:${it.reference}" }) { artifact ->
                    TextButton(onClick = {
                        if (!pending) {
                            val request = ++generation
                            pending = true
                            inspected = null
                            scope.launch {
                                try {
                                    val result = port.inspect(identity, artifact)
                                    if (request != generation) return@launch
                                    when (result) {
                                        is UiWorkArtifactInspectionReceipt.Ready -> {
                                            inspected = result.artifact
                                            feedback = null
                                        }
                                        is UiWorkArtifactInspectionReceipt.Failed -> feedback = result.message ?: unavailable
                                        is UiWorkArtifactInspectionReceipt.Rejected -> feedback = unavailable
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                finally { if (request == generation) pending = false }
                            }
                        }
                    }, enabled = !pending && artifact.currentlyAvailable != false && artifact.sourceCallId != null) {
                        Text(artifact.reference + " · " + stringResource(R.string.next_evidence_inspect))
                    }
                }
                inspected?.let { verified ->
                    item {
                        Text(stringResource(R.string.next_evidence_version, verified.versionNow.orEmpty().take(16)))
                        TextButton(onClick = {
                            if (!pending && verified.versionNow != null) {
                                val request = ++generation
                                pending = true
                                scope.launch {
                                    try {
                                        val result = port.submit(LinkWorkEvidenceReferenceCommand(
                                            identity, requirement.index, requirement.text, verified,
                                            expectedGoalId = snapshot?.goalId, verifyGoalId = true))
                                        if (request != generation) return@launch
                                        feedback = when (result) {
                                            UiCommandReceipt.Accepted -> linked
                                            is UiCommandReceipt.Failed -> result.message ?: unavailable
                                            else -> unavailable
                                        }
                                        if (result == UiCommandReceipt.Accepted) {
                                            inspected = null
                                            val reread = port.read(identity)
                                            if (request == generation && reread is UiWorkEvidenceReadReceipt.Available) snapshot = reread.snapshot
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    finally { if (request == generation) pending = false }
                                }
                            }
                        }, enabled = !pending && verified.versionNow != null) {
                            Text(stringResource(R.string.next_evidence_confirm))
                        }
                    }
                }
            }
            feedback?.let { item { Text(it) } }
        } }, confirmButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.next_close)) } },
        dismissButton = { TextButton(onClick = { refresh() }, enabled = !pending) { Text(stringResource(R.string.next_evidence_refresh)) } })
}

package com.labteto.dshmobile.uipack.v6

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
fun V6WorkEvidencePanel(port: WorkEvidenceUiPort, identity: UiSessionIdentity) {
    var generation by remember(identity) { mutableStateOf(0L) }
    var open by remember(identity) { mutableStateOf(false) }
    var snapshot by remember(identity) { mutableStateOf<UiWorkEvidenceSnapshot?>(null) }
    var selected by remember(identity) { mutableStateOf<UiWorkRequirement?>(null) }
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    var pending by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.v6_evidence_unavailable)
    val linked = stringResource(R.string.v6_evidence_linked)
    fun refresh() {
        if (pending) return
        val request = ++generation
        pending = true
        scope.launch {
            try {
                val result = port.read(identity)
                if (request != generation) return@launch
                when (result) {
                    is UiWorkEvidenceReadReceipt.Available -> { snapshot = result.snapshot; open = true }
                    is UiWorkEvidenceReadReceipt.Failed -> feedback = result.message ?: unavailable
                    is UiWorkEvidenceReadReceipt.Rejected -> feedback = unavailable
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { if (request == generation) pending = false }
        }
    }
    fun close() { generation++; open = false; pending = false }
    TextButton(onClick = { open = true; refresh() }, enabled = !pending) { Text(stringResource(R.string.v6_evidence)) }
    if (open) AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.v6_evidence)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text(stringResource(R.string.v6_evidence_not_verified)) }
            items(snapshot?.requirements.orEmpty(), key = { it.index }) { requirement ->
                com.shenyan.designsystem.components.SyMaterialFrame {
                    TextButton(onClick = { selected = requirement }, enabled = !pending) { Text("${requirement.text} · ${requirement.reportedStatus}") }
                }
                snapshot?.references?.filter { it.requirementIndex == requirement.index && it.requirement == requirement.text }?.forEach { reference ->
                    Text(reference.artifactPath)
                    Text(stringResource(when {
                        reference.versionNow == null -> R.string.v6_artifact_unavailable
                        reference.versionNow != reference.versionAtLink -> R.string.v6_artifact_changed
                        else -> R.string.v6_evidence_source_current
                    }))
                }
            }
            selected?.let { requirement ->
                item { Text(requirement.text) }
                items(snapshot?.artifacts.orEmpty().filter { it.category == "file" }, key = { "${it.asOfSequence}:${it.reference}" }) { artifact ->
                    TextButton(onClick = {
                        if (!pending) { val request = ++generation; pending = true; scope.launch {
                            try {
                                val result = port.submit(LinkWorkEvidenceReferenceCommand(identity, requirement.index, requirement.text, artifact))
                                if (request != generation) return@launch
                                feedback = when (result) {
                                    UiCommandReceipt.Accepted -> linked
                                    is UiCommandReceipt.Failed -> result.message
                                    else -> unavailable
                                }
                                if (result == UiCommandReceipt.Accepted) {
                                    val reread = port.read(identity)
                                    if (request == generation && reread is UiWorkEvidenceReadReceipt.Available) snapshot = reread.snapshot
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            finally { if (request == generation) pending = false }
                        } }
                    }, enabled = !pending && artifact.currentlyAvailable != false && artifact.sourceCallId != null) { Text(artifact.reference) }
                }
            }
            feedback?.let { item { Text(it) } }
        } }, confirmButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.v6_close)) } },
        dismissButton = { TextButton(onClick = { refresh() }, enabled = !pending) { Text(stringResource(R.string.v6_evidence_refresh)) } })
}

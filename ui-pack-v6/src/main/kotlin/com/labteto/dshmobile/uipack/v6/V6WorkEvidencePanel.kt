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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

/**
 * A change in goal, task list or active Owner binding invalidates any selected requirement.
 * In particular, a same-index requirement in a replacement Work goal is a different task.
 */
internal fun v6EvidenceSelectionObsolete(
    before: UiWorkEvidenceSnapshot?, now: UiWorkEvidenceSnapshot,
): Boolean = before != null &&
    (before.identity != now.identity || before.goalId != now.goalId ||
        before.requirements != now.requirements)

/** File versions and origin validity are returned by Work, never inferred from model text. */
@Composable
fun V6WorkEvidencePanel(port: WorkEvidenceUiPort, identity: UiSessionIdentity,
    openOnEntry: Boolean = false) {
    var generation by remember(identity) { mutableStateOf(0L) }
    var open by remember(identity) { mutableStateOf(false) }
    var snapshot by remember(identity) { mutableStateOf<UiWorkEvidenceSnapshot?>(null) }
    var selected by remember(identity) { mutableStateOf<UiWorkRequirement?>(null) }
    var inspected by remember(identity) { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem?>(null) }
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
                    is UiWorkEvidenceReadReceipt.Available -> {
                        snapshot = result.snapshot; selected = null; inspected = null; open = true
                    }
                    is UiWorkEvidenceReadReceipt.Failed -> feedback = result.message ?: unavailable
                    is UiWorkEvidenceReadReceipt.Rejected -> feedback = unavailable
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { if (request == generation) pending = false }
        }
    }
    fun close() { generation++; open = false; pending = false; selected = null; inspected = null }
    LaunchedEffect(identity, openOnEntry) {
        if (openOnEntry) {
            open = true
            refresh()
        }
    }
    // Subscribe only while visible to the original Work/Session Owners. No timer and
    // no stale health/verification claim from a cached projection.
    LaunchedEffect(port, identity, open) {
        if (!open) return@LaunchedEffect
        port.changes(identity).collectLatest {
            if (!open || pending) return@collectLatest
            val observedGeneration = generation
            val result = try {
                withContext(Dispatchers.IO) { port.read(identity) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiWorkEvidenceReadReceipt.Failed(failure.message) }
            if (!open || pending || observedGeneration != generation) return@collectLatest
            when (result) {
                is UiWorkEvidenceReadReceipt.Available -> {
                    if (v6EvidenceSelectionObsolete(snapshot, result.snapshot)) {
                        selected = null
                        inspected = null
                        feedback = "Work 目标或任务已变更，已取消旧证据选择"
                    } else if (snapshot?.artifacts != result.snapshot.artifacts ||
                        snapshot?.references != result.snapshot.references) {
                        inspected = null
                    }
                    snapshot = result.snapshot
                }
                is UiWorkEvidenceReadReceipt.Rejected -> {
                    selected = null
                    inspected = null
                    snapshot = null
                    feedback = "当前会话身份已失效，请返回 Work 后重新打开"
                }
                is UiWorkEvidenceReadReceipt.Failed ->
                    feedback = result.message ?: "证据状态无法刷新，现有显示可能已过期"
            }
        }
    }
    TextButton(onClick = { open = true; refresh() }, enabled = !pending) { Text(stringResource(R.string.v6_evidence)) }
    if (open) AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.v6_evidence)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text(stringResource(R.string.v6_evidence_not_verified)) }
            items(snapshot?.requirements.orEmpty(), key = { it.index }) { requirement ->
                com.shenyan.designsystem.components.SyMaterialFrame {
                    TextButton(onClick = { selected = requirement; inspected = null }, enabled = !pending) { Text("${requirement.text} · ${requirement.reportedStatus}") }
                }
                snapshot?.references?.filter { it.requirementIndex == requirement.index && it.requirement == requirement.text }?.forEach { reference ->
                    Text(reference.artifactPath)
                    Text(stringResource(when {
                        reference.versionNow == null -> R.string.v6_artifact_unavailable
                        reference.versionNow != reference.versionAtLink -> R.string.v6_artifact_changed
                        else -> R.string.v6_evidence_source_current
                    }))
                    val reviewed = snapshot?.reviews?.firstOrNull { it.linkSequence == reference.eventSequence }
                    Text(when {
                        reference.versionNow != reference.versionAtLink -> "文件版本已变化，原人工核验不再有效"
                        reviewed == null -> "尚未进行人工内容核验"
                        reviewed.approvedByUser -> "用户已确认内容符合（不代表自动测试通过）"
                        else -> "用户已标记内容不符合要求"
                    })
                    val displayedGoalId = snapshot?.goalId
                    if (!displayedGoalId.isNullOrBlank() &&
                        reference.versionNow == reference.versionAtLink) {
                        listOf(true to "确认内容符合要求", false to "标记内容不符合").forEach { (approved, title) ->
                            TextButton(onClick = {
                                if (!pending) {
                                    val request = ++generation
                                    pending = true
                                    scope.launch {
                                        try {
                                            val result = port.review(ReviewWorkEvidenceCommand(
                                                identity, displayedGoalId,
                                                requirement.index, requirement.text,
                                                reference.artifactPath, reference.eventSequence,
                                                reference.versionAtLink, approved,
                                            ))
                                            if (request != generation) return@launch
                                            feedback = when (result) {
                                                UiCommandReceipt.Accepted -> "已记录用户人工核验，请注意文件后续修改会使该核验失效"
                                                is UiCommandReceipt.Failed -> result.message ?: unavailable
                                                else -> "核验未被接受，文件或任务可能已变化"
                                            }
                                            if (result == UiCommandReceipt.Accepted) {
                                                val reread = port.read(identity)
                                                if (request == generation && reread is UiWorkEvidenceReadReceipt.Available)
                                                    snapshot = reread.snapshot
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            if (request == generation) feedback = failure.message ?: unavailable
                                        } finally { if (request == generation) pending = false }
                                    }
                                }
                            }, enabled = !pending) { Text(title) }
                        }
                    }
                }
            }
            selected?.let { requirement ->
                item { Text(requirement.text) }
                items(snapshot?.artifacts.orEmpty().filter { it.category == "file" }, key = { "${it.asOfSequence}:${it.reference}" }) { artifact ->
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
                        Text(artifact.reference + " · " + stringResource(R.string.v6_evidence_inspect))
                    }
                }
                inspected?.let { verified ->
                    item {
                        Text(stringResource(R.string.v6_evidence_version, verified.versionNow.orEmpty().take(16)))
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
                            Text(stringResource(R.string.v6_evidence_confirm))
                        }
                    }
                }
            }
            feedback?.let { item { Text(it) } }
        } }, confirmButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.v6_close)) } },
        dismissButton = { TextButton(onClick = { refresh() }, enabled = !pending) { Text(stringResource(R.string.v6_evidence_refresh)) } })
}

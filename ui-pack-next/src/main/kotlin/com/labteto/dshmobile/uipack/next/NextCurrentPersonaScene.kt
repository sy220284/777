package com.labteto.dshmobile.uipack.next

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** A restored draft retains its original activation and revision. It never retargets itself. */
@Composable
internal fun NextCurrentPersonaScene(owner: CurrentPersonaUiPort, close: () -> Unit) {
    var targetJson by rememberSaveable { mutableStateOf<String?>(null) }
    var baseJson by rememberSaveable { mutableStateOf(Json.encodeToString(UiPersonaDraft.serializer(), UiPersonaDraft())) }
    var values by rememberSaveable { mutableStateOf(List(13) { "" }) }
    var sampling by rememberSaveable { mutableStateOf<Int?>(null) }
    var initialPosition by rememberSaveable { mutableIntStateOf(50) }
    var description by rememberSaveable { mutableStateOf("") }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val base = remember(baseJson) { runCatching { Json.decodeFromString(UiPersonaDraft.serializer(), baseJson) }.getOrNull() }
    val target = remember(targetJson) { targetJson?.let { runCatching { Json.decodeFromString(UiCurrentPersonaTarget.serializer(), it) }.getOrNull() } }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.next_current_persona_unavailable)
    val failed = stringResource(R.string.next_persona_save_failed)
    val saved = stringResource(R.string.next_current_persona_saved)
    fun show(draft: UiPersonaDraft) {
        baseJson = Json.encodeToString(UiPersonaDraft.serializer(), draft)
        values = listOf(draft.name, draft.coreIdentity, draft.franchise) + UI_PERSONA_FACT_FIELDS.map { draft.fields[it].orEmpty() }
        sampling = draft.samplingTemperaturePosition
    }
    fun draft() = (base ?: error(unavailable)).copy(name = values[0], coreIdentity = values[1], franchise = values[2],
        fields = UI_PERSONA_FACT_FIELDS.mapIndexed { index, key -> key to values[index + 3] }.toMap(),
        samplingTemperaturePosition = sampling)
    fun reload() { targetJson = null; dirty = false; refresh++ }
    fun requestClose() { if (dirty) confirmAction = "close" else close() }
    BackHandler { if (!busy) requestClose() }
    LaunchedEffect(owner, refresh) {
        when (val result = owner.read()) {
            is UiCurrentPersonaReceipt.Loaded -> {
                if (targetJson == null) {
                    targetJson = Json.encodeToString(UiCurrentPersonaTarget.serializer(), result.snapshot.target)
                    show(result.snapshot.draft); initialPosition = result.snapshot.samplingPosition; feedback = null
                } else if (result.snapshot.target != target || base == null) feedback = unavailable
            }
            else -> feedback = unavailable
        }
    }
    if (confirmAction != null) AlertDialog(onDismissRequest = { confirmAction = null },
        title = { Text(stringResource(R.string.next_persona_discard)) },
        confirmButton = { TextButton(onClick = {
            val action = confirmAction; confirmAction = null
            if (action == "reload") reload() else close()
        }) { Text(stringResource(R.string.next_persona_discard_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmAction = null }) { Text(stringResource(R.string.next_persona_keep_editing)) } })
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.next_current_persona), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.next_current_persona_hint))
            TextButton(onClick = { requestClose() }, enabled = !busy) { Text(stringResource(R.string.next_close)) }
            TextButton(onClick = { if (dirty) confirmAction = "reload" else reload() }, enabled = !busy) {
                Text(stringResource(R.string.next_current_persona_reload))
            }
            feedback?.let { Text(it) }
        }
        items(NEXT_PERSONA_FIELD_LABELS.size) { index ->
            OutlinedTextField(values[index], { text ->
                values = values.toMutableList().also { it[index] = text }; dirty = true
            }, enabled = target != null && base != null && !busy, label = { Text(stringResource(NEXT_PERSONA_FIELD_LABELS[index])) },
                modifier = Modifier.fillMaxWidth(), minLines = if (index > 2) 3 else 1)
        }
        item {
            Text(stringResource(R.string.next_current_persona_sampling, sampling ?: initialPosition))
            Slider(value = (sampling ?: initialPosition).toFloat(), onValueChange = {
                sampling = it.roundToInt(); dirty = true
            }, valueRange = 0f..100f, enabled = target != null && base != null && !busy)
            OutlinedTextField(description, { description = it; dirty = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.next_persona_ai_description)) })
            TextButton(enabled = target != null && base != null && !busy, onClick = {
                val captured = target ?: return@TextButton
                val input = draft(); val instruction = description
                busy = true
                scope.launch {
                    try {
                        when (val result = owner.suggest(captured, input, instruction)) {
                            is UiCurrentPersonaReceipt.Suggested -> { show(result.draft); dirty = true; feedback = null }
                            is UiCurrentPersonaReceipt.Failed -> feedback = result.message ?: failed
                            else -> feedback = unavailable
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { feedback = error.message ?: failed }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.next_persona_ai_fill)) }
            Button(enabled = target != null && base != null && !busy, onClick = {
                val captured = target ?: return@Button
                val input = draft()
                busy = true
                scope.launch {
                    try {
                        when (val result = owner.save(captured, input)) {
                            UiCurrentPersonaReceipt.Saved -> { dirty = false; feedback = saved; close() }
                            is UiCurrentPersonaReceipt.Failed -> feedback = result.message ?: failed
                            else -> feedback = unavailable
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { feedback = error.message ?: failed }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.next_current_persona_apply)) }
        }
    }
}

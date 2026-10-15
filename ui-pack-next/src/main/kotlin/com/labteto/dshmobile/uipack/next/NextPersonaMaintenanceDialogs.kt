package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NextPersonaPresetsDialog(owner: PersonaAuthoringUiPort, close: () -> Unit, installed: () -> Unit) {
    val presets = remember(owner) { owner.presets() }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.next_persona_save_failed)
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.next_persona_presets)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { feedback?.let { Text(it) } }
            items(presets, key = { it.id }) { preset ->
                Text(preset.name)
                Text(preset.franchise)
                Text(preset.summary)
                TextButton(enabled = !busy, onClick = {
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            when (val result = owner.installPreset(preset.id)) {
                                is UiPersonaAuthoringReceipt.Saved -> installed()
                                is UiPersonaAuthoringReceipt.Failed -> feedback = result.message ?: failed
                                else -> feedback = failed
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { feedback = error.message ?: failed }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.next_persona_preset_install)) }
            }
        } },
        confirmButton = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.next_close)) } })
}

/** Findings stay provisional. Only explicitly selected additions enter the versioned archive write. */
@Composable
internal fun NextPersonaInspectionDialog(owner: PersonaAuthoringUiPort, selection: PersonaSelectionUiPort,
    galleryId: String, storyId: String?, close: () -> Unit) {
    var result by remember { mutableStateOf<UiPersonaInspection?>(null) }
    var chosen by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.next_persona_save_failed)
    val stale = stringResource(R.string.next_persona_stale)
    val saved = stringResource(R.string.next_persona_suggestions_saved)
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.next_persona_inspection)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item {
                Text(stringResource(R.string.next_persona_inspection_hint))
                feedback?.let { Text(it) }
                if (busy) Text(stringResource(R.string.next_loading))
                TextButton(enabled = !busy, onClick = {
                    val identity = selection.snapshot().identity
                    if (identity == null) { feedback = stale; return@TextButton }
                    busy = true; result = null; chosen = emptySet(); feedback = null
                    scope.launch {
                        try {
                            when (val receipt = owner.inspect(identity, galleryId, storyId)) {
                                is UiPersonaInspectionReceipt.Inspected -> result = receipt.inspection
                                is UiPersonaInspectionReceipt.Failed -> feedback = receipt.message ?: failed
                                UiPersonaInspectionReceipt.Rejected -> feedback = stale
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { feedback = error.message ?: failed }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.next_persona_inspection_start)) }
            }
            result?.let { data ->
                if (data.conflicts.isEmpty() && data.suggestions.isEmpty()) item {
                    Text(stringResource(R.string.next_persona_inspection_empty))
                }
                items(data.conflicts) { conflict ->
                    Text(stringResource(R.string.next_persona_inspection_conflict, personaFieldLabel(conflict.field),
                        conflict.fixedValue, conflict.observedValue, conflict.reason))
                }
                items(data.suggestions.size) { index ->
                    val suggestion = data.suggestions[index]
                    Row {
                        Checkbox(index in chosen, onCheckedChange = { checked ->
                            chosen = if (checked) chosen + index else chosen - index
                        }, enabled = !busy)
                        Column { Text(personaFieldLabel(suggestion.field)); Text(suggestion.value); Text(suggestion.evidence) }
                    }
                }
                item {
                    TextButton(enabled = !busy && chosen.isNotEmpty(), onClick = {
                        if (busy) return@TextButton
                        val selected = data.suggestions.filterIndexed { index, _ -> index in chosen }
                        busy = true
                        scope.launch {
                            try {
                                when (val receipt = owner.applySuggestions(galleryId, data.revision, selected)) {
                                    is UiPersonaAuthoringReceipt.Saved -> {
                                        result = null; chosen = emptySet(); feedback = saved
                                    }
                                    is UiPersonaAuthoringReceipt.Failed -> feedback = receipt.message ?: failed
                                    else -> feedback = stale
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { feedback = error.message ?: failed }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.next_persona_suggestions_apply)) }
                }
            }
        } },
        confirmButton = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.next_close)) } })
}

@Composable
private fun personaFieldLabel(field: String): String = stringResource(when (field) {
    "coreIdentity" -> R.string.next_persona_identity
    "franchise" -> R.string.next_persona_franchise
    "personality" -> R.string.next_persona_personality
    "valuesAndTradeoffs" -> R.string.next_persona_values
    "selfNarrative" -> R.string.next_persona_narrative
    "subjectiveBeliefs" -> R.string.next_persona_beliefs
    "identityGap" -> R.string.next_persona_gap
    "preferencesAndHabits" -> R.string.next_persona_preferences
    "voiceStyle" -> R.string.next_persona_voice
    "biography" -> R.string.next_persona_biography
    "relationships" -> R.string.next_persona_relationships
    else -> R.string.next_persona_facts
})

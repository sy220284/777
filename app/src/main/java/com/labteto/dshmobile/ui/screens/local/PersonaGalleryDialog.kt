package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

@Composable
internal fun PersonaGallerySavePromptDialog(
    persona: PersonaProfile,
    canSave: Boolean,
    onSaveCurrent: suspend (String, String?) -> Result<PersonaGalleryEntry>,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)

    DsDialog(title = stringResource(R.string.persona_gallery_new_character_title), onDismiss = onDismiss) {
        PersonaHero(persona = persona, subtitle = stringResource(R.string.persona_gallery_new_character_subtitle))
        Text(
            stringResource(R.string.persona_gallery_new_character_hint),
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )
        DsButton(
            text = if (busy) stringResource(R.string.persona_gallery_saving) else stringResource(R.string.persona_gallery_save_enter),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    onSaveCurrent("", null)
                        .onSuccess { onContinue() }
                        .onFailure { error = it.message ?: saveFailedText }
                    busy = false
                }
            },
            enabled = canSave && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            text = stringResource(R.string.persona_gallery_skip_save),
            onClick = onContinue,
            variant = DsButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        error?.let {
            Text(it, style = DsType.small13, color = DsTheme.colors.error)
        }
    }
}

@Composable
internal fun PersonaGalleryDialog(
    entries: List<PersonaGalleryEntry>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
    currentSessionId: String,
    canSave: Boolean,
    onSaveCurrent: suspend (String, String?) -> Result<PersonaGalleryEntry>,
    onEditNotes: suspend (String, String) -> Result<Unit>,
    onInspect: suspend (String) -> Result<PersonaInspectionResult>,
    onApplySuggestions: suspend (String, List<PersonaAppendSuggestion>) -> Result<PersonaGalleryEntry>,
    onDelete: suspend (String) -> Result<Unit>,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    var search by remember { mutableStateOf("") }
    var notes by remember(selectedId, selected?.storyNotes) { mutableStateOf(selected?.storyNotes.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var visibleHistory by remember(selectedId) { mutableStateOf(8) }
    var showHistory by remember(selectedId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember(selectedId) { mutableStateOf<String?>(null) }
    var inspection by remember(selectedId) { mutableStateOf<PersonaInspectionResult?>(null) }
    var inspecting by remember(selectedId) { mutableStateOf(false) }
    val selectedSuggestionKeys = remember(selectedId) { mutableStateListOf<String>() }
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)
    val inspectFailedText = stringResource(R.string.persona_gallery_inspect_failed)
    val appendFailedText = stringResource(R.string.persona_gallery_append_failed)
    val updateFailedText = stringResource(R.string.persona_gallery_update_failed)
    val deleteFailedText = stringResource(R.string.persona_gallery_delete_failed)
    val mergedNoticeText = stringResource(R.string.persona_gallery_merged_notice)
    val appendDoneText = stringResource(R.string.persona_gallery_append_done)
    val storySavedText = stringResource(R.string.persona_gallery_story_saved)
    val mergeDoneText = stringResource(R.string.persona_gallery_merge_done)

    DsDialog(title = if (selected == null) stringResource(R.string.persona_gallery_title) else selected.persona.name, onDismiss = onDismiss) {
        if (selected == null) {
            GalleryOverviewHeader(entries.size)
            DsButton(
                text = if (currentGalleryId == null) stringResource(R.string.persona_gallery_sync_current, currentPersona.name) else stringResource(R.string.persona_gallery_update_current, currentPersona.name),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        onSaveCurrent("", currentGalleryId)
                            .onSuccess {
                                selectedId = it.id
                                notice = mergedNoticeText
                            }
                            .onFailure { error = it.message ?: saveFailedText }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy,
                variant = DsButtonVariant.Outline,
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.persona_gallery_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val query = search.trim()
            val filtered = entries.filter {
                query.isBlank() ||
                    it.persona.name.contains(query, ignoreCase = true) ||
                    it.persona.identity.contains(query, ignoreCase = true) ||
                    it.persona.worldSetting.contains(query, ignoreCase = true) ||
                    it.storyNotes.contains(query, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                DsCard {
                    Text(
                        if (entries.isEmpty()) stringResource(R.string.persona_gallery_empty)
                        else stringResource(R.string.persona_gallery_no_match),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    filtered.forEach { entry ->
                        GalleryPersonaCard(
                            entry = entry,
                            onClick = {
                                selectedId = entry.id
                                error = null
                                notice = null
                            },
                        )
                    }
                }
            }
        } else {
            val dialogueSummary = stringResource(R.string.persona_gallery_dialogue_count, selected.history.size)
            val relationSummary = if (selected.chatState.dynamics.sharedMoments.isNotEmpty()) {
                dialogueSummary + " · " + stringResource(
                    R.string.persona_gallery_moment_count,
                    selected.chatState.dynamics.sharedMoments.size,
                )
            } else {
                dialogueSummary
            }
            PersonaHero(
                persona = selected.persona,
                subtitle = relationSummary,
            )
            notice?.let {
                Surface(
                    color = DsTheme.colors.accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = DsType.small13,
                        color = DsTheme.colors.labelPrimary,
                        modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    )
                }
            }

            PersonaDetails(selected.persona)

            if (selected.chatState.updatedAt > 0L) {
                DsCard {
                    Text(stringResource(R.string.persona_gallery_story_relation), style = DsType.std14, color = DsTheme.colors.labelPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.persona_gallery_current_relation, selected.chatState.relationshipState),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                    selected.chatState.dynamics.sharedMoments.takeLast(4).takeIf { it.isNotEmpty() }?.let { moments ->
                        Text(
                            stringResource(R.string.persona_gallery_shared_moments, moments.joinToString("；")),
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                    selected.chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let { threads ->
                        Text(
                            stringResource(R.string.persona_gallery_unresolved, threads.joinToString("；")),
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                }
            }

            DsButton(
                text = if (inspecting) stringResource(R.string.persona_gallery_inspecting) else stringResource(R.string.persona_gallery_inspect),
                onClick = {
                    inspecting = true
                    error = null
                    notice = null
                    selectedSuggestionKeys.clear()
                    scope.launch {
                        onInspect(selected.id)
                            .onSuccess { inspection = it }
                            .onFailure { error = it.message ?: inspectFailedText }
                        inspecting = false
                    }
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !inspecting,
            )

            inspection?.let { result ->
                PersonaInspectionPanel(
                    result = result,
                    selectedKeys = selectedSuggestionKeys,
                    onToggle = { suggestion ->
                        val key = suggestionKey(suggestion)
                        if (key in selectedSuggestionKeys) selectedSuggestionKeys.remove(key)
                        else selectedSuggestionKeys.add(key)
                    },
                )
                if (result.suggestions.isNotEmpty()) {
                    val chosen = result.suggestions.filter { suggestionKey(it) in selectedSuggestionKeys }
                    DsButton(
                        text = stringResource(R.string.persona_gallery_append_selected, chosen.size),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                onApplySuggestions(selected.id, chosen)
                                    .onSuccess {
                                        inspection = null
                                        selectedSuggestionKeys.clear()
                                        notice = appendDoneText
                                    }
                                    .onFailure { error = it.message ?: appendFailedText }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = chosen.isNotEmpty() && !busy,
                    )
                }
            }

            DsButton(
                text = if (showHistory) stringResource(R.string.persona_gallery_history_hide) else stringResource(R.string.persona_gallery_history_show),
                onClick = { showHistory = !showHistory },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showHistory) {
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = DsTheme.colors.labelSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.persona_gallery_archived_dialogue), style = DsType.std14, color = DsTheme.colors.labelPrimary)
                    }
                    val history = selected.history.filter { it.role == "user" || it.role == "assistant" }
                    history.takeLast(visibleHistory).forEach { line ->
                        Text(
                            "${if (line.role == "user") stringResource(R.string.persona_gallery_user) else selected.persona.name}：${line.content}",
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                    if (history.size > visibleHistory) {
                        DsButton(
                            text = stringResource(R.string.persona_gallery_older_dialogue),
                            onClick = { visibleHistory += 12 },
                            modifier = Modifier.fillMaxWidth(),
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.persona_gallery_story_notes)) },
                placeholder = { Text(stringResource(R.string.persona_gallery_story_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                enabled = !busy,
            )
            if (notes != selected.storyNotes) {
                DsButton(
                    text = stringResource(R.string.persona_gallery_save_story),
                    onClick = {
                        busy = true
                        scope.launch {
                            onEditNotes(selected.id, notes)
                                .onSuccess { notice = storySavedText }
                                .onFailure { error = it.message ?: saveFailedText }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }

            if (canSave && (currentSessionId == selected.sourceSessionId || currentGalleryId == selected.id)) {
                DsButton(
                    text = stringResource(R.string.persona_gallery_merge_current),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onSaveCurrent(notes, selected.id)
                                .onSuccess { notice = mergeDoneText }
                                .onFailure { error = it.message ?: updateFailedText }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                )
            }

            DsButton(
                text = stringResource(R.string.persona_gallery_start_new),
                onClick = { onStart(selected.id) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy && notes == selected.storyNotes,
            )

            if (deleting) {
                DsCard {
                    Text(
                        stringResource(R.string.persona_gallery_delete_confirm),
                        style = DsType.small13,
                        color = DsTheme.colors.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DsButton(
                            text = stringResource(R.string.persona_gallery_cancel),
                            onClick = { deleting = false },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                        )
                        DsButton(
                            text = stringResource(R.string.persona_gallery_confirm_delete),
                            onClick = {
                                busy = true
                                scope.launch {
                                    onDelete(selected.id)
                                        .onSuccess {
                                            selectedId = null
                                            deleting = false
                                        }
                                        .onFailure { error = it.message ?: deleteFailedText }
                                    busy = false
                                }
                            },
                            variant = DsButtonVariant.Danger,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                DsButton(
                    text = stringResource(R.string.persona_gallery_delete),
                    onClick = { deleting = true },
                    variant = DsButtonVariant.Ghost,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DsButton(
                text = stringResource(R.string.persona_gallery_back),
                onClick = {
                    selectedId = null
                    deleting = false
                    error = null
                    notice = null
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let {
            Text(
                it,
                style = DsType.small13,
                color = DsTheme.colors.error,
                modifier = Modifier.padding(top = DsSpacing.small),
            )
        }
    }
}

@Composable
private fun GalleryOverviewHeader(count: Int) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = DsTheme.colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = DsTheme.colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.persona_gallery_master),
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.persona_gallery_count, count),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun GalleryPersonaCard(entry: PersonaGalleryEntry, onClick: () -> Unit) {
    val waitingText = stringResource(R.string.persona_gallery_waiting)
    val subtitle = when {
        entry.persona.identity.isNotBlank() -> entry.persona.identity
        entry.persona.personality.isNotBlank() -> entry.persona.personality
        else -> waitingText
    }
    DsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(entry.persona.name)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.persona.name,
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GalleryPill(stringResource(R.string.persona_gallery_dialogue_count, entry.history.size))
            if (entry.chatState.dynamics.sharedMoments.isNotEmpty()) {
                GalleryPill(stringResource(R.string.persona_gallery_moments_short, entry.chatState.dynamics.sharedMoments.size))
            }
            if (entry.persona.corrections.isNotEmpty()) {
                GalleryPill(stringResource(R.string.persona_gallery_corrections_count, entry.persona.corrections.size))
            }
        }
    }
}

@Composable
private fun PersonaHero(persona: PersonaProfile, subtitle: String) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(persona.name, large = true)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    persona.name,
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    persona.identity.ifBlank { subtitle },
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.identity.isNotBlank()) {
                    Text(subtitle, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
                }
            }
        }
    }
}

@Composable
private fun PersonaAvatar(name: String, large: Boolean = false) {
    val size = if (large) 56.dp else 44.dp
    val fallback = stringResource(R.string.persona_gallery_avatar_fallback)
    val initial = name.trim().firstOrNull()?.toString().orEmpty().ifBlank { fallback }
    Surface(
        shape = CircleShape,
        color = DsTheme.colors.accent.copy(alpha = 0.13f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initial,
                style = if (large) DsType.large20 else DsType.std14,
                color = DsTheme.colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GalleryPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = DsTheme.colors.bgLayer1,
    ) {
        Text(
            text,
            style = DsType.caption11,
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PersonaDetails(persona: PersonaProfile) {
    val scalarSections = listOf(
        stringResource(R.string.persona_field_identity) to persona.identity,
        stringResource(R.string.persona_field_background) to persona.background,
        stringResource(R.string.persona_field_personality) to persona.personality,
        stringResource(R.string.persona_field_speech_style) to persona.speechStyle,
        stringResource(R.string.persona_field_relationship) to persona.relationship,
        stringResource(R.string.persona_field_world_setting) to persona.worldSetting,
    ).filter { it.second.isNotBlank() }
    val listSections = listOf(
        stringResource(R.string.persona_field_constraints) to persona.hardConstraints,
        stringResource(R.string.persona_field_dialogues) to persona.exampleDialogues,
        stringResource(R.string.persona_field_banned) to persona.bannedPhrases,
        stringResource(R.string.persona_field_signature) to persona.signaturePhrases,
        stringResource(R.string.persona_field_corrections) to persona.corrections,
    ).filter { it.second.isNotEmpty() }

    if (scalarSections.isEmpty() && listSections.isEmpty()) return
    DsCard {
        Text(
            stringResource(R.string.persona_gallery_fixed_persona),
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        scalarSections.forEach { (title, value) ->
            PersonaDetailRow(title, value)
        }
        listSections.forEach { (title, values) ->
            PersonaDetailRow(title, values.joinToString("；"))
        }
    }
}

@Composable
private fun PersonaDetailRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        Text(value, style = DsType.small13, color = DsTheme.colors.labelSecondary)
    }
}

@Composable
private fun PersonaInspectionPanel(
    result: PersonaInspectionResult,
    selectedKeys: List<String>,
    onToggle: (PersonaAppendSuggestion) -> Unit,
) {
    if (result.conflicts.isEmpty() && result.suggestions.isEmpty()) {
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PersonSearch,
                    contentDescription = null,
                    tint = DsTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_inspection_clean),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
        return
    }

    if (result.conflicts.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_conflict_count, result.conflicts.size),
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        result.conflicts.forEach { conflict ->
            DsCard {
                Text(
                    personaFieldLabel(conflict.field),
                    style = DsType.caption11,
                    color = DsTheme.colors.error,
                )
                if (conflict.fixedValue.isNotBlank()) {
                    Text(
                        stringResource(R.string.persona_gallery_fixed_value, conflict.fixedValue),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
                Text(
                    stringResource(R.string.persona_gallery_observed_value, conflict.observedValue),
                    style = DsType.small13,
                    color = DsTheme.colors.labelPrimary,
                )
                if (conflict.reason.isNotBlank()) {
                    Text(
                        conflict.reason,
                        style = DsType.caption11,
                        color = DsTheme.colors.labelTertiary,
                    )
                }
            }
        }
    }

    if (result.suggestions.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_suggestions_title),
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        result.suggestions.forEach { suggestion ->
            val key = suggestionKey(suggestion)
            DsCard(onClick = { onToggle(suggestion) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = key in selectedKeys,
                        onCheckedChange = { onToggle(suggestion) },
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            personaFieldLabel(suggestion.field),
                            style = DsType.caption11,
                            color = DsTheme.colors.accent,
                        )
                        Text(
                            suggestion.value,
                            style = DsType.small13,
                            color = DsTheme.colors.labelPrimary,
                        )
                        if (suggestion.evidence.isNotBlank()) {
                            Text(
                                stringResource(R.string.persona_gallery_evidence, suggestion.evidence),
                                style = DsType.caption11,
                                color = DsTheme.colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun suggestionKey(suggestion: PersonaAppendSuggestion): String =
    "${suggestion.field}|${suggestion.value.trim()}"

@Composable
private fun personaFieldLabel(field: String): String = when (field) {
    "identity" -> stringResource(R.string.persona_field_identity)
    "background" -> stringResource(R.string.persona_field_background)
    "personality" -> stringResource(R.string.persona_field_personality)
    "speechStyle" -> stringResource(R.string.persona_field_speech_style)
    "relationship" -> stringResource(R.string.persona_field_relationship)
    "worldSetting" -> stringResource(R.string.persona_field_world_setting)
    "hardConstraints" -> stringResource(R.string.persona_field_constraints)
    "exampleDialogues" -> stringResource(R.string.persona_field_dialogues)
    "bannedPhrases" -> stringResource(R.string.persona_field_banned)
    "signaturePhrases" -> stringResource(R.string.persona_field_signature)
    "corrections" -> stringResource(R.string.persona_field_corrections)
    else -> field
}

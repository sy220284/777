package com.labteto.dshmobile.ui.screens.local

import java.text.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.local.chat.ChatDiaryDisclosure
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.ChatDiarySourceMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.chatRelationshipSubjectKey
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsPageEmptyState
import com.labteto.dshmobile.ui.components.DsPageLoadingState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.DsSegmentedTabs
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class CharacterDiarySubject(
    val key: String,
    val name: String,
)

internal enum class CharacterDiaryFilter {
    ALL,
    DIRECT,
    GROUP,
    PRIVATE,
}

internal fun characterDiarySubjects(
    gallery: List<PersonaGalleryEntry>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
): List<CharacterDiarySubject> {
    val subjects = linkedMapOf<String, CharacterDiarySubject>()
    chatRelationshipSubjectKey(currentGalleryId, currentPersona.id)?.let { key ->
        subjects[key] = CharacterDiarySubject(
            key = key,
            name = currentPersona.name.ifBlank { currentGalleryId ?: currentPersona.id },
        )
    }
    gallery.forEach { entry ->
        val key = "gallery:${entry.id}"
        subjects.putIfAbsent(
            key,
            CharacterDiarySubject(
                key = key,
                name = entry.persona.name.ifBlank { entry.id },
            ),
        )
    }
    return subjects.values.toList()
}

internal fun filterCharacterDiaryEntries(
    entries: List<ChatDiaryEntry>,
    query: String,
    filter: CharacterDiaryFilter,
): List<ChatDiaryEntry> {
    val normalizedQuery = query.trim().lowercase()
    return entries.asSequence()
        .filter { entry ->
            when (filter) {
                CharacterDiaryFilter.ALL -> true
                CharacterDiaryFilter.DIRECT -> entry.sourceMode == ChatDiarySourceMode.DIRECT
                CharacterDiaryFilter.GROUP -> entry.sourceMode == ChatDiarySourceMode.GROUP
                CharacterDiaryFilter.PRIVATE -> entry.disclosure == ChatDiaryDisclosure.PRIVATE
            }
        }
        .filter { entry ->
            normalizedQuery.isBlank() || listOf(
                entry.event,
                entry.feeling,
                entry.innerThought,
                entry.relationshipMeaning,
                entry.unresolvedEcho,
                entry.personaName,
            ).any { it.lowercase().contains(normalizedQuery) }
        }
        .sortedByDescending(ChatDiaryEntry::updatedAt)
        .toList()
}

@Composable
internal fun CharacterDiaryScreen(
    gallery: List<PersonaGalleryEntry>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
    loadEntries: suspend (String) -> List<ChatDiaryEntry>,
    onCorrectEntry: suspend (String, String, Long, ChatDiaryDelta) -> Boolean,
    onDeactivateEntry: suspend (String, String, Long) -> Boolean,
    onOpenSourceSession: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    val subjects = remember(gallery, currentPersona, currentGalleryId) {
        characterDiarySubjects(gallery, currentPersona, currentGalleryId)
    }
    val preferredKey = remember(currentPersona.id, currentGalleryId) {
        chatRelationshipSubjectKey(currentGalleryId, currentPersona.id)
    }
    var selectedSubjectKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<ChatDiaryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var editingEntry by remember { mutableStateOf<ChatDiaryEntry?>(null) }
    var deactivatingEntry by remember { mutableStateOf<ChatDiaryEntry?>(null) }
    var editDraft by remember { mutableStateOf(ChatDiaryDelta()) }
    var mutating by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf(false) }
    LaunchedEffect(selectedSubjectKey) {
        editingEntry = null
        deactivatingEntry = null
        sourceError = false
    }

    LaunchedEffect(subjects, preferredKey) {
        if (selectedSubjectKey == null || subjects.none { it.key == selectedSubjectKey }) {
            selectedSubjectKey = preferredKey?.takeIf { key -> subjects.any { it.key == key } }
                ?: subjects.firstOrNull()?.key
        }
    }
    LaunchedEffect(selectedSubjectKey, reloadNonce) {
        val key = selectedSubjectKey
        if (key == null) {
            entries = emptyList()
            loadFailed = false
            loading = false
        } else {
            loading = true
            loadFailed = false
            runCatching { loadEntries(key) }
                .onSuccess { entries = it }
                .onFailure {
                    entries = emptyList()
                    loadFailed = true
                }
            loading = false
        }
    }

    val filters = CharacterDiaryFilter.entries
    val selectedFilter = filters.getOrElse(selectedFilterIndex) { CharacterDiaryFilter.ALL }
    val visibleEntries = remember(entries, query, selectedFilter) {
        filterCharacterDiaryEntries(entries, query, selectedFilter)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            DsTopBar(
                title = stringResource(R.string.chat_diary_title),
                subtitle = stringResource(R.string.chat_diary_subtitle),
                onBack = onDismiss,
                backContentDescription = stringResource(R.string.common_back),
                largeTitle = true,
            )

            if (subjects.isEmpty()) {
                DsPageEmptyState(
                    icon = FeatherIcons.BookOpen,
                    title = stringResource(R.string.chat_diary_no_character_title),
                    body = stringResource(R.string.chat_diary_no_character_body),
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = DsSpacing.comfortable,
                    vertical = DsSpacing.medium,
                ),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                if (subjects.size > 1) {
                    item(key = "diary-subjects") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            items(subjects, key = CharacterDiarySubject::key) { subject ->
                                val selected = subject.key == selectedSubjectKey
                                DsPill(
                                    text = subject.name,
                                    modifier = Modifier.semantics { this.selected = selected },
                                    selected = selected,
                                    onClick = {
                                        selectedSubjectKey = subject.key
                                        query = ""
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "diary-search") {
                    DsTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.chat_diary_search_hint)) },
                        leadingIcon = {
                            Icon(
                                FeatherIcons.Search,
                                contentDescription = null,
                                tint = colors.labelTertiary,
                            )
                        },
                        singleLine = true,
                        shape = DsShapes.block,
                    )
                }
                if (sourceError) {
                    item(key = "diary-source-error") {
                        Text(stringResource(R.string.chat_diary_source_unavailable),
                            color = colors.error, style = DsType.small13.withReadingWeight())
                    }
                }
                item(key = "diary-filters") {
                    DsSegmentedTabs(
                        labels = listOf(
                            stringResource(R.string.chat_diary_filter_all),
                            stringResource(R.string.chat_diary_filter_direct),
                            stringResource(R.string.chat_diary_filter_group),
                            stringResource(R.string.chat_diary_filter_private),
                        ),
                        selectedIndex = selectedFilterIndex,
                        onSelect = { selectedFilterIndex = it },
                    )
                }

                when {
                    loading -> item(key = "diary-loading") {
                        DsPageLoadingState(
                            icon = FeatherIcons.BookOpen,
                            label = stringResource(R.string.chat_diary_loading),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    loadFailed -> item(key = "diary-load-failed") {
                        DsPageEmptyState(
                            icon = FeatherIcons.AlertTriangle,
                            title = stringResource(R.string.chat_diary_load_failed_title),
                            body = stringResource(R.string.chat_diary_load_failed_body),
                            actionText = stringResource(R.string.common_retry),
                            onAction = { reloadNonce++ },
                            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xlarge),
                        )
                    }
                    visibleEntries.isEmpty() -> item(key = "diary-empty") {
                        DsPageEmptyState(
                            icon = if (query.isNotBlank()) FeatherIcons.Search else FeatherIcons.BookOpen,
                            title = stringResource(
                                if (query.isNotBlank()) R.string.chat_diary_no_match_title
                                else R.string.chat_diary_empty_title,
                            ),
                            body = stringResource(
                                if (query.isNotBlank()) R.string.chat_diary_no_match_body
                                else R.string.chat_diary_empty_body,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xxlarge),
                        )
                    }
                    else -> items(visibleEntries, key = ChatDiaryEntry::id) { entry ->
                        CharacterDiaryEntryCard(
                            entry = entry,
                            onEdit = {
                                editingEntry = entry
                                editDraft = ChatDiaryDelta(
                                    event = entry.event, feeling = entry.feeling,
                                    innerThought = entry.innerThought,
                                    relationshipMeaning = entry.relationshipMeaning,
                                    unresolvedEcho = entry.unresolvedEcho,
                                    importance = entry.importance,
                                )
                                mutationError = false
                            },
                            onDeactivate = {
                                deactivatingEntry = entry
                                mutationError = false
                            },
                            onOpenSource = { sessionId ->
                                if (onOpenSourceSession(sessionId)) {
                                    sourceError = false
                                } else {
                                    sourceError = true
                                }
                            },
                        )
                    }
                }
            }

        }
    }
    editingEntry?.let { entry ->
        DsBottomSheet(
            title = stringResource(R.string.chat_diary_edit_title),
            subtitle = stringResource(R.string.chat_diary_edit_hint),
            onDismiss = { if (!mutating) editingEntry = null },
            dismissEnabled = !mutating,
            scrollable = true,
            footer = {
                DsButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        mutating = true
                        mutationError = false
                        scope.launch {
                            val success = try {
                                onCorrectEntry(entry.subjectKey, entry.id, entry.updatedAt, editDraft)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            mutating = false
                            if (success) editingEntry = null else mutationError = true
                            reloadNonce++
                        }
                    },
                    enabled = editDraft.event.trim().isNotBlank() && !mutating,
                    modifier = Modifier.fillMaxWidth(),
                    loading = mutating,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editingEntry = null },
                    enabled = !mutating,
                    variant = DsButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            DsTextField(
                value = editDraft.event, onValueChange = { editDraft = editDraft.copy(event = it) },
                label = { Text(stringResource(R.string.chat_diary_edit_event)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 5,
            )
            DsTextField(
                value = editDraft.feeling, onValueChange = { editDraft = editDraft.copy(feeling = it) },
                label = { Text(stringResource(R.string.chat_diary_feeling)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            DsTextField(
                value = editDraft.innerThought, onValueChange = { editDraft = editDraft.copy(innerThought = it) },
                label = { Text(stringResource(R.string.chat_diary_inner_thought)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            DsTextField(
                value = editDraft.relationshipMeaning,
                onValueChange = { editDraft = editDraft.copy(relationshipMeaning = it) },
                label = { Text(stringResource(R.string.chat_diary_relationship_meaning)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            DsTextField(
                value = editDraft.unresolvedEcho,
                onValueChange = { editDraft = editDraft.copy(unresolvedEcho = it) },
                label = { Text(stringResource(R.string.chat_diary_unresolved_echo)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            if (mutationError) Text(
                stringResource(R.string.chat_diary_save_failed),
                style = DsType.small13.withReadingWeight(), color = colors.error,
            )
        }
    }
    deactivatingEntry?.let { entry ->
        DsBottomSheet(
            title = stringResource(R.string.chat_diary_deactivate),
            onDismiss = { if (!mutating) deactivatingEntry = null },
            dismissEnabled = !mutating,
            footer = {
                DsButton(
                    text = stringResource(R.string.chat_diary_deactivate),
                    onClick = {
                        mutating = true
                        mutationError = false
                        scope.launch {
                            val success = try {
                                onDeactivateEntry(entry.subjectKey, entry.id, entry.updatedAt)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            mutating = false
                            if (success) deactivatingEntry = null else mutationError = true
                            reloadNonce++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !mutating,
                    loading = mutating,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deactivatingEntry = null },
                    enabled = !mutating, variant = DsButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            Text(stringResource(R.string.chat_diary_deactivate_hint),
                style = DsType.std14.withReadingWeight(), color = colors.labelSecondary)
            if (mutationError) Text(stringResource(R.string.chat_diary_save_failed),
                style = DsType.small13.withReadingWeight(), color = colors.error)
        }
    }
}

@Composable
private fun CharacterDiaryEntryCard(
    entry: ChatDiaryEntry,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val dateText = remember(entry.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(entry.updatedAt)
    }
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                dateText,
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
            DsPill(
                text = stringResource(
                    if (entry.sourceMode == ChatDiarySourceMode.GROUP) {
                        R.string.chat_diary_source_group
                    } else {
                        R.string.chat_diary_source_direct
                    },
                ),
            )
            DsPill(text = diaryDisclosureLabel(entry.disclosure))
        }

        Text(
            entry.event,
            style = DsType.base16Strong.withReadingWeight().copy(fontFamily = DsType.contentFont),
            color = colors.labelPrimary,
        )

        CharacterDiaryNarrativeBlock(
            label = stringResource(R.string.chat_diary_feeling),
            text = entry.feeling,
        )
        CharacterDiaryNarrativeBlock(
            label = stringResource(R.string.chat_diary_inner_thought),
            text = entry.innerThought,
        )
        CharacterDiaryNarrativeBlock(
            label = stringResource(R.string.chat_diary_relationship_meaning),
            text = entry.relationshipMeaning,
        )
        CharacterDiaryNarrativeBlock(
            label = stringResource(R.string.chat_diary_unresolved_echo),
            text = entry.unresolvedEcho,
        )

        if (entry.revisions.lastOrNull()?.userCorrected == true) {
            DsPill(text = stringResource(R.string.chat_diary_user_corrected))
        }
        entry.sources.lastOrNull()?.takeIf { it.sessionId.isNotBlank() }?.let { source ->
            Text(
                stringResource(
                    R.string.chat_diary_source_ref,
                    source.sessionId.take(12),
                    source.userMessageId.ifBlank { source.assistantMessageId }.take(12),
                ),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
            DsButton(
                text = stringResource(R.string.chat_diary_open_source),
                onClick = { onOpenSource(source.sessionId) },
                variant = DsButtonVariant.Ghost,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(text = stringResource(R.string.chat_diary_edit_title),
                onClick = onEdit, variant = DsButtonVariant.Ghost)
            DsButton(text = stringResource(R.string.chat_diary_deactivate),
                onClick = onDeactivate, variant = DsButtonVariant.Ghost)
        }
        Text(
            stringResource(R.string.chat_diary_importance, entry.importance),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelCaption,
        )
    }
}

@Composable
private fun CharacterDiaryNarrativeBlock(label: String, text: String) {
    if (text.isBlank()) return
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        Text(
            label,
            style = DsType.caption11Strong.withReadingWeight(),
            color = colors.accent,
        )
        Text(
            text,
            style = DsType.std14.withReadingWeight().copy(fontFamily = DsType.contentFont),
            color = colors.labelSecondary,
        )
    }
}

@Composable
private fun diaryDisclosureLabel(disclosure: ChatDiaryDisclosure): String =
    stringResource(
        when (disclosure) {
            ChatDiaryDisclosure.PRIVATE -> R.string.chat_diary_disclosure_private
            ChatDiaryDisclosure.SHAREABLE -> R.string.chat_diary_disclosure_shareable
            ChatDiaryDisclosure.PUBLIC -> R.string.chat_diary_disclosure_public
        },
    )

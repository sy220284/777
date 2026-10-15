package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import java.text.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.local.chat.ChatDiaryDisclosure
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.ChatDiarySourceMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.chatRelationshipSubjectKey
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyPill
import com.shenyan.designsystem.components.SyEmptyState
import com.shenyan.designsystem.components.SyLoadingState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SySegmentedTabs
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.labteto.dshmobile.ui.theme.DsShapes
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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
    // Subject changes must never expose a previous character's diary while the new
    // projection is loading. Cancellation is not a load failure; let Compose stop it.
    var loadedSubjectKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedSubjectKey, reloadNonce) {
        val key = selectedSubjectKey
        loadedSubjectKey = null
        entries = emptyList()
        loadFailed = false
        if (key == null) {
            loading = false
        } else {
            loading = true
            try {
                val fresh = loadEntries(key)
                entries = fresh
                loadedSubjectKey = key
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                entries = emptyList()
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    val filters = CharacterDiaryFilter.entries
    val selectedFilter = filters.getOrElse(selectedFilterIndex) { CharacterDiaryFilter.ALL }
    val visibleEntries = remember(entries, query, selectedFilter, selectedSubjectKey, loadedSubjectKey) {
        if (loadedSubjectKey == selectedSubjectKey && selectedSubjectKey != null) {
            filterCharacterDiaryEntries(entries, query, selectedFilter)
        } else emptyList()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            SyTopBar(
                title = stringResource(R.string.chat_diary_title),
                subtitle = stringResource(R.string.chat_diary_subtitle),
                onBack = onDismiss,
                backContentDescription = stringResource(R.string.common_back),
                largeTitle = true,
            )

            if (subjects.isEmpty()) {
                SyEmptyState(
                    icon = null,
                    title = stringResource(R.string.chat_diary_no_character_title),
                    body = stringResource(R.string.chat_diary_no_character_body),
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = SyMetric.gapSection,
                    vertical = SyMetric.gapLg,
                ),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
            ) {
                if (subjects.size > 1) {
                    item(key = "diary-subjects") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                            items(subjects, key = CharacterDiarySubject::key) { subject ->
                                val selected = subject.key == selectedSubjectKey
                                SyPill(
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
                    SyTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.chat_diary_search_hint)) },
                        leadingIcon = {
                            Icon(
                                FeatherIcons.Search,
                                contentDescription = null,
                                tint = colors.textTertiary,
                            )
                        },
                        singleLine = true,
                        shape = DsShapes.block,
                    )
                }
                if (sourceError) {
                    item(key = "diary-source-error") {
                        Text(stringResource(R.string.chat_diary_source_unavailable),
                            color = colors.dangerInk, style = DsType.small13.withReadingWeight())
                    }
                }
                item(key = "diary-filters") {
                    SySegmentedTabs(
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
                        SyLoadingState(
                            icon = FeatherIcons.BookOpen,
                            label = stringResource(R.string.chat_diary_loading),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    loadFailed -> item(key = "diary-load-failed") {
                        SyEmptyState(
                            icon = null,
                            title = stringResource(R.string.chat_diary_load_failed_title),
                            body = stringResource(R.string.chat_diary_load_failed_body),
                            actionText = stringResource(R.string.common_retry),
                            onAction = { reloadNonce++ },
                            modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapPage),
                        )
                    }
                    visibleEntries.isEmpty() -> item(key = "diary-empty") {
                        SyEmptyState(
                            icon = null,
                            title = stringResource(
                                if (query.isNotBlank()) R.string.chat_diary_no_match_title
                                else R.string.chat_diary_empty_title,
                            ),
                            body = stringResource(
                                if (query.isNotBlank()) R.string.chat_diary_no_match_body
                                else R.string.chat_diary_empty_body,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapHero),
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
        SyResponsiveSheet(
            title = stringResource(R.string.chat_diary_edit_title),
            subtitle = stringResource(R.string.chat_diary_edit_hint),
            onDismiss = { if (!mutating) editingEntry = null },
            dismissEnabled = !mutating,
            scrollable = true,
            footer = {
                SyButton(
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
                    modifier = Modifier.fillMaxWidth().testTag("diary_save"),
                    loading = mutating,
                )
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editingEntry = null },
                    enabled = !mutating,
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            SyTextField(
                value = editDraft.event, onValueChange = { editDraft = editDraft.copy(event = it) },
                label = { Text(stringResource(R.string.chat_diary_edit_event)) },
                modifier = Modifier.fillMaxWidth().testTag("diary_edit_event"), enabled = !mutating, maxLines = 5,
            )
            SyTextField(
                value = editDraft.feeling, onValueChange = { editDraft = editDraft.copy(feeling = it) },
                label = { Text(stringResource(R.string.chat_diary_feeling)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            SyTextField(
                value = editDraft.innerThought, onValueChange = { editDraft = editDraft.copy(innerThought = it) },
                label = { Text(stringResource(R.string.chat_diary_inner_thought)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            SyTextField(
                value = editDraft.relationshipMeaning,
                onValueChange = { editDraft = editDraft.copy(relationshipMeaning = it) },
                label = { Text(stringResource(R.string.chat_diary_relationship_meaning)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            SyTextField(
                value = editDraft.unresolvedEcho,
                onValueChange = { editDraft = editDraft.copy(unresolvedEcho = it) },
                label = { Text(stringResource(R.string.chat_diary_unresolved_echo)) },
                modifier = Modifier.fillMaxWidth(), enabled = !mutating, maxLines = 4,
            )
            if (mutationError) Text(
                stringResource(R.string.chat_diary_save_failed),
                style = DsType.small13.withReadingWeight(), color = colors.dangerInk,
            )
        }
    }
    deactivatingEntry?.let { entry ->
        SyResponsiveSheet(
            title = stringResource(R.string.chat_diary_deactivate),
            onDismiss = { if (!mutating) deactivatingEntry = null },
            dismissEnabled = !mutating,
            seamlessBackground = false,
            footer = {
                SyButton(
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
                    modifier = Modifier.fillMaxWidth().testTag("diary_confirm_deactivate"),
                    enabled = !mutating,
                    loading = mutating,
                )
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deactivatingEntry = null },
                    enabled = !mutating, variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            Text(stringResource(R.string.chat_diary_deactivate_hint),
                style = DsType.std14.withReadingWeight(), color = colors.textSecondary)
            if (mutationError) Text(stringResource(R.string.chat_diary_save_failed),
                style = DsType.small13.withReadingWeight(), color = colors.dangerInk)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterDiaryEntryCard(
    entry: ChatDiaryEntry,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val dateText = remember(entry.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(entry.updatedAt)
    }
    // Reference screen 09: a centered reading sheet, with real recorded diary fields.
    // Never synthesize first-person thoughts or comments that do not exist in the diary store.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg)) {
        Text(
            dateText,
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            entry.event,
            style = DsType.base16Strong.withReadingWeight().copy(fontFamily = DsType.contentFont),
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("diary_paper_event_${entry.id}"),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            SyPill(
                text = stringResource(
                    if (entry.sourceMode == ChatDiarySourceMode.GROUP) {
                        R.string.chat_diary_source_group
                    } else {
                        R.string.chat_diary_source_direct
                    },
                ),
            )
            SyPill(text = diaryDisclosureLabel(entry.disclosure))
        }

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
            SyPill(text = stringResource(R.string.chat_diary_user_corrected))
        }
        entry.sources.lastOrNull()?.takeIf { it.sessionId.isNotBlank() }?.let { source ->
            Text(
                stringResource(
                    R.string.chat_diary_source_ref,
                    source.sessionId.take(12),
                    source.userMessageId.ifBlank { source.assistantMessageId }.take(12),
                ),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
            SyButton(
                text = stringResource(R.string.chat_diary_open_source),
                onClick = { onOpenSource(source.sessionId) },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.testTag("diary_source_${entry.id}"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            SyButton(text = stringResource(R.string.chat_diary_edit_title),
                onClick = onEdit, variant = SyButtonVariant.Ghost,
                modifier = Modifier.testTag("diary_edit_${entry.id}"))
            SyButton(text = stringResource(R.string.chat_diary_deactivate),
                onClick = onDeactivate, variant = SyButtonVariant.Ghost,
                modifier = Modifier.testTag("diary_deactivate_${entry.id}"))
        }
        Text(
            stringResource(R.string.chat_diary_importance, entry.importance),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )
    }
    }
}

@Composable
private fun CharacterDiaryNarrativeBlock(label: String, text: String) {
    if (text.isBlank()) return
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
        Text(
            label,
            style = DsType.caption11Strong.withReadingWeight(),
            color = colors.textSecondary,
        )
        Text(
            text,
            style = DsType.chatBody.withReadingWeight(),
            color = colors.textSecondary,
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

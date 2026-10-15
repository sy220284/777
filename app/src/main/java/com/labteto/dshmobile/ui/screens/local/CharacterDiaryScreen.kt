package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
import com.labteto.dshmobile.local.presentation.ChatDiaryReadingVocabulary
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


/** A reading section contains only one date and one experience; independent stories never mix. */
internal data class CharacterDiaryDay(
    val dayStart: Long,
    val experienceId: String,
    val entries: List<ChatDiaryEntry>,
) {
    val key: String get() = dayStart.toString() + ":" + experienceId
}

internal fun groupCharacterDiaryDays(entries: List<ChatDiaryEntry>): List<CharacterDiaryDay> {
    val calendar = Calendar.getInstance()
    return entries.groupBy { entry ->
        // A later manual correction must not move an old memory into today's diary.
        calendar.timeInMillis = entry.createdAt.takeIf { it > 0L } ?: entry.updatedAt
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.timeInMillis to entry.experienceId.ifBlank {
            entry.sources.lastOrNull()?.sessionId.orEmpty()
        }
    }.map { (identity, records) ->
        CharacterDiaryDay(identity.first, identity.second, records.sortedBy(ChatDiaryEntry::createdAt))
    }.sortedWith(compareByDescending<CharacterDiaryDay> { it.dayStart }
        .thenByDescending { it.entries.maxOf(ChatDiaryEntry::updatedAt) })
}

/**
 * Reading-only prose: retain the stored event as evidence, but hide technical field labels.
 * Do not invent any emotion or rewrite the persisted facts / source references.
 */
internal fun characterDiaryReadingLine(entry: ChatDiaryEntry): String {
    // Older records are evidence, not fiction to be rewritten: never fabricate a speaker
    // or prepend "today" to a dated entry that may describe an earlier encounter.
    fun clean(text: String): String = text.trim()
        .replace(ChatDiaryReadingVocabulary.TECHNICAL_USER, ChatDiaryReadingVocabulary.OTHER_PERSON)
        .trimEnd('。', '，', '；', ' ', '\n')
    val event = clean(entry.event)
    val thoughts = listOf(
        entry.feeling,
        entry.innerThought,
        entry.relationshipMeaning,
        entry.unresolvedEcho,
    ).map(::clean).filter(String::isNotBlank).distinct().filterNot { it == event }
    val parts = mutableListOf<String>()
    // A very long factual anchor can crowd out the part that makes this a diary.
    // Keep the event only when it fits naturally alongside a subjective thought.
    if (event.isNotBlank() && (thoughts.isEmpty() || event.length <= 90)) parts += event
    thoughts.forEach { thought ->
        if (parts.size < 3 && parts.sumOf(String::length) + thought.length +
            parts.size * 2 <= 320
        ) parts += thought
    }
    if (parts.isEmpty() && event.isNotBlank()) parts += event
    return parts.joinToString("。") { it.trimEnd('。', '！', '？', '；') }
        .let { if (it.isBlank()) "" else it.trimEnd('。') + "。" }
}

internal fun characterDiaryDayNarrative(entries: List<ChatDiaryEntry>): String {
    // Mental and emotional observations deserve a place before routine event-only notes.
    // Keep complete thoughts. Never chop a diary sentence at an arbitrary character index.
    val selected = entries.sortedWith(
        compareByDescending<ChatDiaryEntry> {
            listOf(it.feeling, it.innerThought, it.relationshipMeaning, it.unresolvedEcho)
                .count(String::isNotBlank)
        }.thenByDescending { it.importance }.thenByDescending(ChatDiaryEntry::createdAt),
    ).take(3).sortedBy(ChatDiaryEntry::createdAt)
    val paragraphs = mutableListOf<String>()
    selected.map(::characterDiaryReadingLine).filter(String::isNotBlank).distinct()
        .forEach { text ->
            val nextLength = paragraphs.sumOf(String::length) + text.length +
                paragraphs.size * 2
            if (nextLength <= 360) paragraphs += text
        }
    return paragraphs.joinToString("\n\n")
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
    var expandedDayKey by rememberSaveable { mutableStateOf<String?>(null) }
    var browseExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectedSubjectKey) {
        editingEntry = null
        deactivatingEntry = null
        sourceError = false
        expandedDayKey = null
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

    val diaryDays = remember(visibleEntries) { groupCharacterDiaryDays(visibleEntries) }

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
                item(key = "diary-browse-toggle") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SyButton(
                            text = stringResource(
                                if (browseExpanded) R.string.chat_diary_finish_browse
                                else R.string.chat_diary_start_browse,
                            ),
                            onClick = {
                                if (browseExpanded) {
                                    query = ""
                                    selectedFilterIndex = 0
                                }
                                browseExpanded = !browseExpanded
                            },
                            variant = SyButtonVariant.Ghost,
                            modifier = Modifier.testTag("diary_browse_toggle"),
                        )
                    }
                }
                if (browseExpanded) {
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
                }
                if (sourceError) {
                    item(key = "diary-source-error") {
                        Text(stringResource(R.string.chat_diary_source_unavailable),
                            color = colors.dangerInk, style = DsType.small13.withReadingWeight())
                    }
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
                    else -> items(diaryDays, key = CharacterDiaryDay::key) { day ->
                        CharacterDiaryDayCard(
                            day = day,
                            expanded = expandedDayKey == day.key,
                            onToggle = {
                                expandedDayKey = if (expandedDayKey == day.key) null else day.key
                            },
                            onEdit = { entry ->
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
                            onDeactivate = { entry ->
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
private fun CharacterDiaryDayCard(
    day: CharacterDiaryDay,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: (ChatDiaryEntry) -> Unit,
    onDeactivate: (ChatDiaryEntry) -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val dateText = remember(day.dayStart) {
        DateFormat.getDateInstance(DateFormat.FULL, Locale.CHINA).format(Date(day.dayStart))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                .padding(vertical = SyMetric.gapMd),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Text(
                text = dateText,
                style = DsType.caption11Strong.withReadingWeight(),
                color = colors.textTertiary,
                modifier = Modifier.fillMaxWidth().testTag("diary_day_" + day.key),
            )
            // Default presentation is prose, not a five-column psychological analysis.
            Text(
                text = characterDiaryDayNarrative(day.entries),
                style = DsType.chatBody.withReadingWeight().copy(fontFamily = DsType.contentFont),
                color = colors.text,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().testTag(
                    "diary_paper_event_" + day.entries.first().id,
                ),
            )
            SyButton(
                text = stringResource(
                    if (expanded) R.string.chat_diary_collapse_records
                    else R.string.chat_diary_expand_records, day.entries.size,
                ),
                onClick = onToggle,
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.testTag("diary_expand_" + day.entries.first().id),
            )
            if (expanded) {
                day.entries.forEach { entry ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapSm),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    ) {
                        Text(
                            text = entry.event.replace(
                                ChatDiaryReadingVocabulary.TECHNICAL_USER,
                                ChatDiaryReadingVocabulary.OTHER_PERSON,
                            ),
                            style = DsType.std14.withReadingWeight(),
                            color = colors.textSecondary,
                            modifier = Modifier.testTag("diary_raw_event_" + entry.id),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                        ) {
                            SyPill(text = stringResource(
                                if (entry.sourceMode == ChatDiarySourceMode.GROUP) {
                                    R.string.chat_diary_source_group
                                } else R.string.chat_diary_source_direct,
                            ))
                            SyPill(text = diaryDisclosureLabel(entry.disclosure))
                            if (entry.revisions.lastOrNull()?.userCorrected == true) {
                                SyPill(text = stringResource(R.string.chat_diary_user_corrected))
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                        ) {
                            entry.sources.lastOrNull()?.takeIf { it.sessionId.isNotBlank() }?.let { source ->
                                SyButton(
                                    text = stringResource(R.string.chat_diary_open_source),
                                    onClick = { onOpenSource(source.sessionId) },
                                    variant = SyButtonVariant.Ghost,
                                    modifier = Modifier.testTag("diary_source_" + entry.id),
                                )
                            }
                            SyButton(
                                text = stringResource(R.string.chat_diary_edit_title),
                                onClick = { onEdit(entry) },
                                variant = SyButtonVariant.Ghost,
                                modifier = Modifier.testTag("diary_edit_" + entry.id),
                            )
                            SyButton(
                                text = stringResource(R.string.chat_diary_deactivate),
                                onClick = { onDeactivate(entry) },
                                variant = SyButtonVariant.Ghost,
                                modifier = Modifier.testTag("diary_deactivate_" + entry.id),
                            )
                        }
                    }
                }
            }
        }
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

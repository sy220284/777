package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Archive navigation and maintenance use the same Chat Owners as the conversation entry. */
@Composable
fun NextPersonaGalleryScene(
    gallery: PersonaGalleryUiPort,
    selection: PersonaSelectionUiPort,
    onBack: () -> Unit,
    onStoryOpened: () -> Boolean,
    authoring: PersonaAuthoringUiPort? = null,
    transfer: PersonaTransferUiPort? = null,
    stories: PersonaStoryUiPort? = null,
    deletion: PersonaGalleryDeletionUiPort? = null,
    save: PersonaGallerySaveUiPort? = null,
    portraits: PersonaPortraitUiPort? = null,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var manageTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val selectionState by selection.snapshots.collectAsStateWithLifecycle(initialValue = selection.snapshot())
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    if (authoring != null && (creating || editing != null)) {
        NextPersonaAuthoringScene(authoring, selection, editing,
            onBack = { creating = false; editing = null },
            onSaved = { creating = false; editing = null })
        return
    }
    var refresh by remember { mutableIntStateOf(0) }
    var receipt by remember(gallery) { mutableStateOf<UiPersonaGalleryReadReceipt?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val unavailable = stringResource(R.string.next_gallery_unavailable)
    val unknown = stringResource(R.string.next_unknown)
    val rejected = stringResource(R.string.next_gallery_action_rejected)
    val navigationFailed = stringResource(R.string.next_gallery_navigation_failed)

    if (managing) NextPersonaGalleryDialog(
        port = gallery, personas = selection, selection = selectionState,
        transfer = transfer, stories = stories, deletion = deletion, save = save, portraits = portraits,
        initialGalleryId = manageTarget, onSelectionAccepted = onStoryOpened,
        close = { managing = false; refresh++ },
    )
    LaunchedEffect(gallery, refresh) {
        receipt = null
        try { receipt = gallery.read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { receipt = UiPersonaGalleryReadReceipt.Failed(error.message) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.next_gallery_archive),
                style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.next_gallery_close)) }
        }
        TextButton(onClick = { refresh++ }, enabled = !busy) {
            Text(stringResource(R.string.next_gallery_refresh))
        }
        if (authoring != null) TextButton(onClick = { creating = true }, enabled = !busy) {
            Text(stringResource(R.string.next_persona_create))
        }
        if (transfer != null || save != null) TextButton(
            onClick = { manageTarget = null; managing = true }, enabled = !busy,
        ) { Text(stringResource(R.string.next_gallery_manage)) }
        feedback?.let { Text(it) }
        when (val data = receipt) {
            null -> Text(stringResource(R.string.next_loading))
            is UiPersonaGalleryReadReceipt.Failed ->
                Text(data.message?.takeIf(String::isNotBlank) ?: unavailable)
            is UiPersonaGalleryReadReceipt.Available -> {
                if (data.entries.isEmpty()) Text(stringResource(R.string.next_gallery_empty))
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.entries, key = { it.id }) { persona ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(persona.name, style = MaterialTheme.typography.titleMedium)
                            if (authoring != null) TextButton(onClick = { editing = persona.id }, enabled = !busy) {
                                Text(stringResource(R.string.next_persona_edit))
                            }
                            if (stories != null || deletion != null || portraits != null || transfer != null) {
                                TextButton(onClick = { manageTarget = persona.id; managing = true }, enabled = !busy) {
                                    Text(stringResource(R.string.next_gallery_manage_persona))
                                }
                            }
                            if (persona.stories.isEmpty()) {
                                Text(stringResource(R.string.next_gallery_no_stories))
                            }
                            persona.stories.forEach { story ->
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(story.title, style = MaterialTheme.typography.bodyLarge)
                                        if (story.storyStage.isNotBlank()) Text(story.storyStage)
                                    }
                                    Button(
                                        enabled = !busy,
                                        onClick = {
                                            val snapshot = selection.snapshot()
                                            val id = snapshot.identity
                                            if (id == null || !snapshot.canStartStory) {
                                                feedback = snapshot.unavailableReason ?: unavailable
                                            } else {
                                                busy = true
                                                try {
                                                    when (val result = selection.submit(
                                                        PersonaSelectionUiCommand.StartStory(
                                                            id, persona.id, story.id, freshStory = false))) {
                                                        UiCommandReceipt.Accepted -> {
                                                            // The Owner has committed the story switch. A failed Host
                                                            // transition must not repeat that irreversible operation.
                                                            feedback = if (onStoryOpened()) null else navigationFailed
                                                        }
                                                        UiCommandReceipt.Queued,
                                                        UiCommandReceipt.OutcomeUnknown -> feedback = unknown
                                                        is UiCommandReceipt.Rejected -> feedback = rejected
                                                        is UiCommandReceipt.Failed -> feedback = result.message
                                                        UiCommandReceipt.NotAdmitted -> feedback = unavailable
                                                        is UiCommandReceipt.MessageEdited -> feedback = unavailable
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    feedback = error.message ?: unavailable
                                                } finally { busy = false }
                                            }
                                        },
                                    ) { Text(stringResource(R.string.next_gallery_continue_story)) }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

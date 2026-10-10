package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.presentation.findEstablishedGroupChatSession
import com.labteto.dshmobile.local.session.LocalSessionSummary
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun localChatFeatureUiContribution(
    gallery: List<PersonaGalleryEntry>,
    state: StateFlow<LocalConversationSurfaceState>,
    actions: LocalChatFeatureUiActions,
    onResetNavigation: () -> Unit,
    onNewPersona: () -> Unit,
    onPopFeature: () -> Unit,
    sessions: List<LocalSessionSummary>,
    onSwitchSession: (String) -> Boolean,
    onOpenGroupSetup: () -> Unit,
    onPromptPersonaSave: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
): LocalFeatureUiContribution {
    val surface by state.collectAsStateWithLifecycle()
    return LocalFeatureUiContribution(
        moduleId = LocalFeatureModuleId.CHAT,
        drawerActions = mapOf(
            LocalFeatureDrawerEntry.GROUP_CHAT to {
                val establishedSessionId = findEstablishedGroupChatSession(sessions)?.id
                if (establishedSessionId != null) {
                    if (onSwitchSession(establishedSessionId)) onResetNavigation()
                } else {
                    onOpenGroupSetup()
                }
                onCloseDrawer()
            },
            LocalFeatureDrawerEntry.PERSONA_GALLERY to {
                onCloseDrawer()
                if (actions.hasUnsavedCurrentPersona()) onPromptPersonaSave()
                else onOpenFromDrawer(LocalFeaturePage.PERSONA_GALLERY)
            },
            LocalFeatureDrawerEntry.PERSONA_GALLERY_CONTINUE to {
                onOpenFromDrawer(LocalFeaturePage.PERSONA_GALLERY)
            },
            LocalFeatureDrawerEntry.DIARY to {
                onOpenFromDrawer(LocalFeaturePage.DIARY)
                onCloseDrawer()
            },
        ),
        backAction = { _, edge -> localFeatureProductBackAction(edge) },
        restorePage = ::localFeatureRestoreOwnedPage,
    ) { page ->
        when (page) {
            LocalFeaturePage.DIARY -> CharacterDiaryScreen(
                gallery = gallery,
                currentPersona = surface.chatPersona,
                currentGalleryId = surface.galleryId,
                loadEntries = actions.diaryEntries,
                onDismiss = onPopFeature,
            )
            LocalFeaturePage.PERSONA_GALLERY -> PersonaGalleryScreen(
                entries = gallery,
                presets = actions.personaPresets,
                currentPersona = surface.chatPersona,
                currentGalleryId = surface.galleryId,
                currentGalleryStoryId = surface.galleryStoryId,
                currentHasUnsavedChanges = actions.currentGalleryHasUnsavedChanges(),
                currentSessionId = surface.sessionId,
                canSave = !surface.loading && !surface.running,
                onSaveCurrent = actions.saveCurrentToGallery,
                onEditStoryDetails = actions.editGalleryStoryDetails,
                onRenameStory = actions.renameGalleryStory,
                onInspect = actions.inspectGalleryPersona,
                onApplySuggestions = actions.applyGallerySuggestions,
                onDelete = actions.deleteGalleryEntry,
                onDeleteStory = actions.deleteGalleryStory,
                onDeleteHistoryMessage = actions.deleteGalleryHistoryMessage,
                onExport = actions.exportGalleryPersona,
                onImport = actions.importGalleryPersona,
                onInstallPreset = actions.installPersonaPreset,
                onSetPortrait = actions.setGalleryPortrait,
                onRemovePortrait = actions.removeGalleryPortrait,
                onStart = { id, storyId, freshStory ->
                    if (actions.startFromGallery(id, storyId, freshStory)) onResetNavigation()
                },
                onCreate = onNewPersona,
                onDismiss = onPopFeature,
            )
            else -> error("Chat received route owned by another feature: $page")
        }
    }
}

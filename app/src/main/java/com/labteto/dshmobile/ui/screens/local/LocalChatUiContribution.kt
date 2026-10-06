package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState

internal fun localChatFeatureUiContribution(
    gallery: List<PersonaGalleryEntry>,
    state: LocalConversationSurfaceState,
    viewModel: LocalHarnessViewModel,
    onResetNavigation: () -> Unit,
    onNewPersona: () -> Unit,
    onPopFeature: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(LocalFeatureModuleId.CHAT) { page ->
    when (page) {
        LocalFeaturePage.DIARY -> CharacterDiaryScreen(
            gallery = gallery,
            currentPersona = state.chatPersona,
            currentGalleryId = state.galleryId,
            loadEntries = viewModel::diaryEntries,
            onDismiss = onPopFeature,
        )
        LocalFeaturePage.PERSONA_GALLERY -> PersonaGalleryScreen(
            entries = gallery,
            presets = viewModel.personaPresets,
            currentPersona = state.chatPersona,
            currentGalleryId = state.galleryId,
            currentGalleryStoryId = state.galleryStoryId,
            currentHasUnsavedChanges = viewModel.currentGalleryHasUnsavedChanges(),
            currentSessionId = state.sessionId,
            canSave = !state.loading && !state.running,
            onSaveCurrent = viewModel::saveCurrentToGallery,
            onEditNotes = viewModel::editGalleryNotes,
            onRenameStory = viewModel::renameGalleryStory,
            onInspect = viewModel::inspectGalleryPersona,
            onApplySuggestions = viewModel::applyGallerySuggestions,
            onDelete = viewModel::deleteGalleryEntry,
            onDeleteStory = viewModel::deleteGalleryStory,
            onDeleteHistoryMessage = viewModel::deleteGalleryHistoryMessage,
            onExport = viewModel::exportGalleryPersona,
            onImport = viewModel::importGalleryPersona,
            onInstallPreset = viewModel::installPersonaPreset,
            onSetPortrait = viewModel::setGalleryPortrait,
            onRemovePortrait = viewModel::removeGalleryPortrait,
            onStart = { id, storyId, freshStory ->
                if (viewModel.startFromGallery(id, storyId, freshStory)) onResetNavigation()
            },
            onCreate = onNewPersona,
            onDismiss = onPopFeature,
        )
        else -> error("Chat 收到非所属路由：$page")
    }
}

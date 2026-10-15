package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uipack.next.NextApplicationFeaturePorts
import com.labteto.dshmobile.uipack.next.NextApplicationScenePack
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Renderer registry and strict whole-app rejection, not artificial 78-scene placeholders. */
class NextIncrementalApplicationPackTest {
    private val chat = object : ChatUiPort {
        private val state = ChatSceneSnapshot(
            null, UiSceneStatus.EMPTY, UiExecutionPhase.IDLE, emptyList(),
            0, UiActionAvailability(false), null)
        override val snapshots = MutableStateFlow(state)
        override fun snapshot(): ChatSceneSnapshot = state
        override fun submit(command: ChatUiCommand): UiCommandReceipt = UiCommandReceipt.NotAdmitted
    }
    private val work = object : WorkConversationUiPort {
        private val state = WorkConversationSceneSnapshot(
            null, UiSceneStatus.EMPTY, UiExecutionPhase.IDLE, emptyList(),
            0, UiActionAvailability(false), null, null)
        override val snapshots = MutableStateFlow(state)
        override fun snapshot(): WorkConversationSceneSnapshot = state
        override fun submit(command: WorkConversationUiCommand): UiCommandReceipt =
            UiCommandReceipt.NotAdmitted
    }
    private val drafts = object : ConversationDraftUiPort {
        override val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
        override val attachments =
            MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
        override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {}
        override fun removeAttachment(sessionId: String, relativePath: String) {}
        override fun clearAttachments(sessionId: String) {}
        override fun updateDraft(sessionId: String, text: String) {}
    }
    private val reading = object : ConversationReadingUiPort {
        override fun anchor(sessionId: String): UiReadingAnchor? = null
        override fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor) {}
    }
    private val gallery = object : PersonaGalleryUiPort {
        override suspend fun read(): UiPersonaGalleryReadReceipt =
            UiPersonaGalleryReadReceipt.Available(emptyList())
        override suspend fun submit(command: PersonaGalleryUiCommand): UiCommandReceipt =
            UiCommandReceipt.NotAdmitted
    }
    private val selection = object : PersonaSelectionUiPort {
        private val state = UiPersonaSelectionSnapshot(null, false, false, null)
        override val snapshots = MutableStateFlow(state)
        override fun snapshot(): UiPersonaSelectionSnapshot = state
        override fun submit(command: PersonaSelectionUiCommand): UiCommandReceipt =
            UiCommandReceipt.NotAdmitted
    }

    private fun pack() = NextApplicationScenePack(
        NextApplicationFeaturePorts(chat, work, drafts, reading, gallery, selection, { false }))

    @Test fun realIncrementalPackHasFiveExecutableScenesAndNoFakeFullCoverage() {
        val pack = pack()
        val expected = setOf(
            "route.home", "conversation.chat", "conversation.work",
            "route.persona_gallery", "chat.gallery"
        ).map(::UiSceneId).toSet()
        assertEquals(expected, pack.manifest.scenes)
        assertEquals(expected, pack.renderers.keys)
        assertTrue(UiSceneId("route.settings") !in pack.renderers)
    }

    @Test fun installedIncrementalPackIsStillRejectedByRealProductionCatalog() {
        val pack = pack()
        val result = decideApplicationUi(
            pack.manifest.id, listOf(pack),
            UiPackAdmissionRequirements(
                UI_CONTRACT_VERSION, LocalUiSceneCatalog.requirements,
                pack.manifest.requiredCapabilities, UiWindowClass.COMPACT,
                UiAccessibility.entries.toSet(), false))
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
        val rejection = (result as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(rejection is UiApplicationPackAdmission.Incompatible)
        val missing = (rejection as UiApplicationPackAdmission.Incompatible).compatibility.missingScenes
        assertTrue(UiSceneId("route.settings") in missing)
        assertTrue(UiSceneId("route.diary") in missing)
    }

    @Test fun actualFiveSceneRendererPackAdmitsItsImplementedScopeOnly() {
        val pack = pack()
        val scoped = LocalUiSceneCatalog.requirements.filter { it.id in pack.manifest.scenes }
        val result = UiApplicationPackRegistry(listOf(pack)).select(
            pack.manifest.id,
            UiPackAdmissionRequirements(
                UI_CONTRACT_VERSION, scoped, pack.manifest.requiredCapabilities,
                UiWindowClass.COMPACT, UiAccessibility.entries.toSet(), false))
        assertTrue(result is UiApplicationPackAdmission.Admitted)
    }
}

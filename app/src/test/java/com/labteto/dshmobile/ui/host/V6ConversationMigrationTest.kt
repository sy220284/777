package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.ChatSceneSnapshot
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiActionAvailability
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiHostPreferences
import com.labteto.dshmobile.uicontract.UiHostPreferencesCommand
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiReadingAnchor
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.WorkConversationSceneSnapshot
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uipack.v6.V6ApplicationScenePack
import com.labteto.dshmobile.uipack.v6.V6ConversationPorts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract-level checks on the REAL V6 Host-assembled scene pack.
 * No fake renderer is inserted to satisfy the whole-app 75-scene gate.
 */
class V6ConversationMigrationTest {
    private val preferences = object : HostPreferencesUiPort {
        override val snapshots: Flow<UiHostPreferences> = flowOf(UiHostPreferences())
        override suspend fun submit(command: UiHostPreferencesCommand) = true
        override fun openSystemPermissions() = true
        override fun openSystemNotifications() = true
    }
    private val settings = java.lang.reflect.Proxy.newProxyInstance(
        SettingsUiPort::class.java.classLoader, arrayOf(SettingsUiPort::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "unexecuted-settings-owner"
            "hashCode" -> 42
            "equals" -> false
            else -> error("Unexpected Owner access during renderer registration: ${method.name}")
        }
    } as SettingsUiPort
    private val chat = object : ChatUiPort {
        private val initial = ChatSceneSnapshot(
            null, UiSceneStatus.EMPTY, UiExecutionPhase.IDLE, emptyList(), 0,
            UiActionAvailability(false), null)
        override val snapshots = MutableStateFlow(initial)
        override fun snapshot() = initial
        override fun submit(command: ChatUiCommand): UiCommandReceipt = UiCommandReceipt.NotAdmitted
    }
    private val work = object : WorkConversationUiPort {
        private val initial = WorkConversationSceneSnapshot(
            null, UiSceneStatus.EMPTY, UiExecutionPhase.IDLE, emptyList(), 0,
            UiActionAvailability(false), null, null)
        override val snapshots = MutableStateFlow(initial)
        override fun snapshot() = initial
        override fun submit(command: WorkConversationUiCommand): UiCommandReceipt =
            UiCommandReceipt.NotAdmitted
    }
    private val drafts = object : ConversationDraftUiPort {
        override val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
        override val attachments = MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
        override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {}
        override fun removeAttachment(sessionId: String, relativePath: String) {}
        override fun clearAttachments(sessionId: String) {}
        override fun updateDraft(sessionId: String, text: String) {}
    }
    private val reading = object : ConversationReadingUiPort {
        override fun anchor(sessionId: String): UiReadingAnchor? = null
        override fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor) {}
    }
    private fun pack() = V6ApplicationScenePack(
        preferences, settings,
        V6ConversationPorts(chat, work, drafts, reading, workMode = { false }),
    )
    private fun requirements(manifest: UiPackManifest, onlyDeclared: Boolean) =
        UiPackAdmissionRequirements(
            UI_CONTRACT_VERSION,
            LocalUiSceneCatalog.requirements.filter {
                !onlyDeclared || it.id in manifest.scenes
            },
            manifest.requiredCapabilities, UiWindowClass.COMPACT,
            UiAccessibility.entries.toSet(), false,
        )

    @Test fun independentV6RegistersActualSettingsAndConversationEntryPoints() {
        val candidate = pack()
        val expected = setOf(
            "settings.appearance", "settings.notifications", "settings.permissions",
            "settings.session", "settings.chat",
            "route.home", "conversation.chat", "conversation.work",
        ).map(::UiSceneId).toSet()
        assertEquals(expected, candidate.manifest.scenes)
        assertEquals(expected, candidate.renderers.keys)
    }

    @Test fun scopedEightScenePackPassesContractWithoutExecutingOwners() {
        val candidate = pack()
        assertTrue(UiApplicationPackRegistry(listOf(candidate)).select(
            candidate.manifest.id, requirements(candidate.manifest, onlyDeclared = true),
        ) is UiApplicationPackAdmission.Admitted)
    }

    @Test fun fullProductionCatalogContinuesRejectingIncompleteV6() {
        val candidate = pack()
        val rejected = decideApplicationUi(candidate.manifest.id, listOf(candidate),
            requirements(candidate.manifest, onlyDeclared = false))
        assertTrue(rejected is LocalApplicationUiDecision.Unavailable)
        val problem = (rejected as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(problem is UiApplicationPackAdmission.Incompatible)
        val missing = (problem as UiApplicationPackAdmission.Incompatible).compatibility.missingScenes
        assertTrue(UiSceneId("conversation.composer") in missing)
        assertTrue(UiSceneId("route.settings") in missing)
    }
}

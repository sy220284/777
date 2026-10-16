package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.ChatSceneSnapshot
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.WorkEvidenceUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.WorkPolicyUiPort
import com.labteto.dshmobile.uicontract.WorkTeamUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryUiPort
import com.labteto.dshmobile.uicontract.PersonaSelectionUiPort
import com.labteto.dshmobile.uicontract.ChatGroupUiPort
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
    private val documentPort = java.lang.reflect.Proxy.newProxyInstance(
        UiDocumentUiPort::class.java.classLoader, arrayOf(UiDocumentUiPort::class.java)
    ) { _, method, _ -> error("Owner called during registration: ${method.name}") } as UiDocumentUiPort
    private val formPort = java.lang.reflect.Proxy.newProxyInstance(
        UiDocumentFormUiPort::class.java.classLoader, arrayOf(UiDocumentFormUiPort::class.java)
    ) { _, method, _ -> error("Form Owner called during registration: ${method.name}") } as UiDocumentFormUiPort
    private fun documentCandidate() = V6ApplicationScenePack(
        preferences, settings,
        V6ConversationPorts(chat, work, drafts, reading, documents = documentPort,
            forms = formPort, workMode = { false }),
    )
    private val evidencePort = java.lang.reflect.Proxy.newProxyInstance(
        WorkEvidenceUiPort::class.java.classLoader, arrayOf(WorkEvidenceUiPort::class.java)
    ) { _, method, _ -> error("Evidence Owner called during registry validation: ${method.name}") } as WorkEvidenceUiPort
    private fun fullIncrementalCandidate() = V6ApplicationScenePack(
        preferences, settings,
        V6ConversationPorts(chat, work, drafts, reading, documents = documentPort,
            forms = formPort, evidence = evidencePort, workMode = { true }),
    )
    private val galleryPort = java.lang.reflect.Proxy.newProxyInstance(
        PersonaGalleryUiPort::class.java.classLoader, arrayOf(PersonaGalleryUiPort::class.java)
    ) { _, method, _ -> error("Unexpected gallery read while registering: ${method.name}") } as PersonaGalleryUiPort
    private val selectionPort = java.lang.reflect.Proxy.newProxyInstance(
        PersonaSelectionUiPort::class.java.classLoader, arrayOf(PersonaSelectionUiPort::class.java)
    ) { _, method, _ -> error("Unexpected selection while registering: ${method.name}") } as PersonaSelectionUiPort
    private val groupPort = java.lang.reflect.Proxy.newProxyInstance(
        ChatGroupUiPort::class.java.classLoader, arrayOf(ChatGroupUiPort::class.java)
    ) { _, method, _ -> error("Unexpected group mutation while registering: ${method.name}") } as ChatGroupUiPort
    private fun personaAndGroupCandidate() = V6ApplicationScenePack(
        preferences, settings,
        V6ConversationPorts(chat, work, drafts, reading, documents = documentPort,
            forms = formPort, evidence = evidencePort, gallery = galleryPort,
            selection = selectionPort, groups = groupPort, workMode = { false }),
    )
    private val workReadPort = java.lang.reflect.Proxy.newProxyInstance(
        WorkReadUiPort::class.java.classLoader, arrayOf(WorkReadUiPort::class.java)
    ) { _, method, _ -> error("Work Plan queried during renderer registration: ${method.name}") } as WorkReadUiPort
    private val workPolicyPort = java.lang.reflect.Proxy.newProxyInstance(
        WorkPolicyUiPort::class.java.classLoader, arrayOf(WorkPolicyUiPort::class.java)
    ) { _, method, _ -> error("Work Policy queried during renderer registration: ${method.name}") } as WorkPolicyUiPort
    private val workTeamPort = java.lang.reflect.Proxy.newProxyInstance(
        WorkTeamUiPort::class.java.classLoader, arrayOf(WorkTeamUiPort::class.java)
    ) { _, method, _ -> error("Work Team queried during renderer registration: ${method.name}") } as WorkTeamUiPort
    private fun fullBusinessCandidate() = V6ApplicationScenePack(
        preferences, settings,
        V6ConversationPorts(chat, work, drafts, reading, documents = documentPort,
            forms = formPort, evidence = evidencePort, gallery = galleryPort,
            selection = selectionPort, groups = groupPort, stories = null,
            workReads = workReadPort, workPolicy = workPolicyPort,
            workTeam = workTeamPort, workMode = { true }),
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

    @Test fun independentV6RegistersConversationPagesAndRealSettingsSuite() {
        val candidate = pack()
        val expected = setOf(
            "settings.appearance", "settings.notifications", "settings.permissions",
            "settings.session", "settings.chat",
            "route.home", "conversation.chat", "conversation.work",
            "conversation.header", "conversation.timeline", "conversation.composer",
            "conversation.reply_status", "work.approval", "work.question",
            "settings.root", "route.settings",
            "settings.models", "conversation.model_picker", "settings.account",
            "settings.minimax_quota", "settings.model_performance", "settings.pricing",
            "settings.usage", "settings.model_usage", "settings.usage_log",
            "settings.usage_detail", "settings.memory", "settings.memory_settings",
            "settings.advanced", "settings.session_storage", "settings.diagnostics",
            "conversation.message_actions", "conversation.edit", "conversation.reader",
            "chat.branches", "shell.error",
        ).map(::UiSceneId).toSet()
        assertEquals(expected, candidate.manifest.scenes)
        assertEquals(expected, candidate.renderers.keys)
    }

    @Test fun scopedConversationAndSettingsPackPassesContractWithoutExecutingOwners() {
        val candidate = pack()
        assertTrue(UiApplicationPackRegistry(listOf(candidate)).select(
            candidate.manifest.id, requirements(candidate.manifest, onlyDeclared = true),
        ) is UiApplicationPackAdmission.Admitted)
    }

    @Test fun bothTypedDocumentScenesAreActualRendererEntries() {
        val withoutDocument = pack()
        val withDocument = documentCandidate()
        val expected = setOf(UiSceneId("conversation.ui_document"),
            UiSceneId("conversation.ui_document_generate"))
        assertEquals(withoutDocument.manifest.scenes + expected, withDocument.manifest.scenes)
        assertEquals(withDocument.manifest.scenes, withDocument.renderers.keys)
        assertTrue(UiApplicationPackRegistry(listOf(withDocument)).select(
            withDocument.manifest.id,
            requirements(withDocument.manifest, onlyDeclared = true),
        ) is UiApplicationPackAdmission.Admitted)
    }

    @Test fun fullCatalogStillRejectsEvenWithRealDocumentRenderers() {
        val candidate = documentCandidate()
        val rejection = decideApplicationUi(candidate.manifest.id, listOf(candidate),
            requirements(candidate.manifest, onlyDeclared = false))
        assertTrue(rejection is LocalApplicationUiDecision.Unavailable)
        val incompatible = (rejection as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(incompatible is UiApplicationPackAdmission.Incompatible)
        assertTrue(UiSceneId("automation.tasks") in
            (incompatible as UiApplicationPackAdmission.Incompatible).compatibility.missingScenes)
    }

    @Test fun realEvidenceOwnerSceneIsRegisteredOnlyWhenInjected() {
        val candidate = fullIncrementalCandidate()
        val expected = documentCandidate().manifest.scenes +
            UiSceneId("work.requirement_evidence")
        assertEquals(expected, candidate.manifest.scenes)
        assertEquals(expected, candidate.renderers.keys)
        assertTrue(UiApplicationPackRegistry(listOf(candidate)).select(
            candidate.manifest.id,
            requirements(candidate.manifest, onlyDeclared = true),
        ) is UiApplicationPackAdmission.Admitted)
        val production = decideApplicationUi(candidate.manifest.id, listOf(candidate),
            requirements(candidate.manifest, onlyDeclared = false))
        assertTrue(production is LocalApplicationUiDecision.Unavailable)
    }

    @Test fun personaAndGroupScenesRequireTheActualInjectedOwners() {
        val candidate = personaAndGroupCandidate()
        val expected = fullIncrementalCandidate().manifest.scenes + setOf(
            UiSceneId("chat.persona_picker"), UiSceneId("chat.group_members"),
            UiSceneId("chat.group_announcement"),
            UiSceneId("chat.gallery"), UiSceneId("route.persona_gallery"),
            UiSceneId("chat.gallery_profile"), UiSceneId("chat.gallery_delete"),
        )
        assertEquals(expected, candidate.manifest.scenes)
        assertEquals(expected, candidate.renderers.keys)
        assertTrue(UiApplicationPackRegistry(listOf(candidate)).select(
            candidate.manifest.id,
            requirements(candidate.manifest, onlyDeclared = true),
        ) is UiApplicationPackAdmission.Admitted)
        assertTrue(decideApplicationUi(candidate.manifest.id, listOf(candidate),
            requirements(candidate.manifest, onlyDeclared = false))
            is LocalApplicationUiDecision.Unavailable)
    }

    @Test fun workPlanAndTeamHaveRealConditionalRenderers() {
        val candidate = fullBusinessCandidate()
        val expected = personaAndGroupCandidate().manifest.scenes +
            setOf(UiSceneId("work.plan_review"), UiSceneId("work.team"),
                UiSceneId("work.steering_choice"))
        assertEquals(expected, candidate.manifest.scenes)
        assertEquals(expected, candidate.renderers.keys)
        assertTrue(UiApplicationPackRegistry(listOf(candidate)).select(
            candidate.manifest.id,
            requirements(candidate.manifest, onlyDeclared = true),
        ) is UiApplicationPackAdmission.Admitted)
        assertTrue(decideApplicationUi(candidate.manifest.id, listOf(candidate),
            requirements(candidate.manifest, onlyDeclared = false))
            is LocalApplicationUiDecision.Unavailable)
    }

    @Test fun fullProductionCatalogContinuesRejectingIncompleteV6() {
        val candidate = pack()
        val rejected = decideApplicationUi(candidate.manifest.id, listOf(candidate),
            requirements(candidate.manifest, onlyDeclared = false))
        assertTrue(rejected is LocalApplicationUiDecision.Unavailable)
        val problem = (rejected as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(problem is UiApplicationPackAdmission.Incompatible)
        val missing = (problem as UiApplicationPackAdmission.Incompatible).compatibility.missingScenes
        assertTrue(UiSceneId("conversation.ui_document") in missing)
        assertTrue(UiSceneId("automation.tasks") in missing)
    }
}

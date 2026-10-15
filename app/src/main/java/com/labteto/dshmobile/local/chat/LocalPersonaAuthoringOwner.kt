package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.PersonaAuthoringUiPort
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiPersonaAuthoringReceipt
import com.labteto.dshmobile.uicontract.UiPersonaAuthoringSnapshot
import com.labteto.dshmobile.uicontract.UiPersonaDraft
import com.labteto.dshmobile.uicontract.UiPersonaFact
import com.labteto.dshmobile.uicontract.UiPersonaLore
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun PersonaProfile.authoringDraft() = UiPersonaDraft(name, coreIdentity, franchise,
    CharacterFactCategories.canonical.associateWith { factText(it) },
    facts.map { UiPersonaFact(it.id, it.category, it.content, it.relatedFactIds, it.perspective,
        it.temporalScope, it.provenance.name, it.sourceReference) },
    loreEntries.map { UiPersonaLore(it.id, it.title, it.content, it.keywords, it.secondaryKeywords,
        it.priority, it.alwaysOn, it.spoilerLevel, it.temporalScope) },
    timelinePosition, knowledgeBoundary, hardConstraints, bannedPhrases, corrections, presetId,
    behaviorTuning.samplingTemperaturePosition)

/** Preserve unedited per-fact provenance, scoped facts, world book and non-editor settings. */
internal fun UiPersonaDraft.applyTo(base: PersonaProfile = PersonaProfile()): PersonaProfile {
    require(fields.keys.all { it in CharacterFactCategories.canonical }) { "人物字段不受支持" }
    var profile = base.copy(name = name.trim(), coreIdentity = coreIdentity, franchise = franchise,
        timelinePosition = timelinePosition, knowledgeBoundary = knowledgeBoundary, hardConstraints = hardConstraints,
        bannedPhrases = bannedPhrases, corrections = corrections, presetId = presetId,
        behaviorTuning = base.behaviorTuning.copy(samplingTemperaturePosition = samplingTemperaturePosition).normalized(),
        facts = facts.map { CharacterFact(it.id, it.category, it.content, it.relatedIds, it.perspective,
            it.stage, CharacterFactProvenance.entries.firstOrNull { value -> value.name == it.provenance }
                ?: CharacterFactProvenance.UNVERIFIED, it.source) },
        loreEntries = lore.map { PersonaLoreEntry(it.id, it.title, it.content, it.keywords,
            it.secondaryKeywords, it.priority, it.alwaysOn, it.spoilerLevel, it.stage) })
    fields.forEach { (category, text) -> if (text != profile.factText(category)) profile = profile.withFact(category, text) }
    return profile.canonicalV4()
}

/** Chat owns authoring, model suggestions and the existing gallery's conditional write. */
@Singleton
class LocalPersonaAuthoringOwner @Inject internal constructor(
    private val gallery: ChatPersonaGalleryStore,
    private val suggestions: PersonaAutoFillService,
    private val state: LocalRuntimeStateStore,
) : PersonaAuthoringUiPort {
    override suspend fun read(galleryId: String) = guarded {
        val entry = gallery.findEntry(galleryId) ?: return@guarded UiPersonaAuthoringReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        UiPersonaAuthoringReceipt.Loaded(UiPersonaAuthoringSnapshot(entry.id, entry.updatedAt, entry.persona.authoringDraft()))
    }
    override suspend fun create(draft: UiPersonaDraft) = guarded {
        val profile = draft.applyTo()
        require(isMeaningfulGalleryPersona(profile)) { "人物设定内容不足，无法保存" }
        val entry = gallery.save(profile, "", emptyList(), ChatCharacterState(), "").entry
        UiPersonaAuthoringReceipt.Saved(entry.id, entry.updatedAt)
    }
    override suspend fun save(galleryId: String, expectedRevision: Long, draft: UiPersonaDraft) = guarded {
        val entry = gallery.editProfile(galleryId, expectedRevision) { draft.applyTo(it) }
            ?: return@guarded UiPersonaAuthoringReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        UiPersonaAuthoringReceipt.Saved(entry.id, entry.updatedAt)
    }
    override suspend fun suggest(identity: UiSessionIdentity, draft: UiPersonaDraft, description: String): UiPersonaAuthoringReceipt = guarded {
        val before = synchronized(state.foregroundRunHandle.lock) {
            val current = state.state.value
            if (state.sessionTransitioning || current.loading || current.kernel.running ||
                current.usageMode != LocalUsageMode.CHAT || state.uiBindingIdentity(current) != identity ||
                !current.modelState.configured) null else current
        } ?: return@guarded UiPersonaAuthoringReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        val proposed = suggestions.generate(before.modelState.model, before.modelState.baseUrl,
            before.modelState.modelSelection.activeProfileId, draft.applyTo(), emptyList(), description)
        synchronized(state.foregroundRunHandle.lock) {
            if (state.sessionTransitioning || state.uiBindingIdentity(state.state.value) != identity)
                UiPersonaAuthoringReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            else UiPersonaAuthoringReceipt.Suggested(proposed.authoringDraft())
        }
    }
    private suspend fun guarded(block: suspend () -> UiPersonaAuthoringReceipt): UiPersonaAuthoringReceipt = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiPersonaAuthoringReceipt.Failed(failure.message) }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalPersonaAuthoringBindings {
    @dagger.Binds @Singleton
    abstract fun authoring(owner: LocalPersonaAuthoringOwner): PersonaAuthoringUiPort
}

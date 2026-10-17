package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.toUiAuthoringDraft
import com.labteto.dshmobile.local.presentation.toDomainPersona
import com.labteto.dshmobile.uicontract.CurrentPersonaUiPort
import com.labteto.dshmobile.uicontract.UiCurrentPersonaReceipt

/** V6 adapts its existing form values; Chat owns history, model routing and late-result rejection. */
internal class LocalChatModelAssistController(private val owner: CurrentPersonaUiPort) {
    suspend fun autoFillCurrentPersona(draft: PersonaProfile, description: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Result<PersonaProfile> = runSuspendResult {
        val snapshot = (owner.read() as? UiCurrentPersonaReceipt.Loaded)?.snapshot
            ?: error("persona_autofill_busy")
        check(expected != null && snapshot.target.identity == expected && snapshot.target.personaId == draft.id && snapshot.target.revision == draft.updatedAt) { "persona_autofill_stale" }
        when (val result = owner.suggest(snapshot.target, draft.toUiAuthoringDraft(), description)) {
            is UiCurrentPersonaReceipt.Suggested -> result.draft.toDomainPersona(draft)
            is UiCurrentPersonaReceipt.Failed -> error(result.message ?: "persona_autofill_unconfigured")
            else -> error("persona_autofill_stale")
        }
    }
}

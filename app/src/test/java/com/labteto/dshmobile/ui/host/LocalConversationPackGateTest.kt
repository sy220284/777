package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiPackAdmission
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uipack.next.NextConversationUiPack
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual production admission uses Host-defined scenes and connected UI ports. */
class LocalConversationPackGateTest {
    private val allConnected = NextConversationUiPack.manifest.requiredCapabilities

    @Test fun connectedSliceIsAdmittedButCannotClaimWholeAppCoverage() {
        assertTrue(LocalConversationPackGate.admit(
            NextConversationUiPack.manifest.id, allConnected, UiWindowClass.COMPACT, false,
        ) is UiPackAdmission.Admitted)
    }

    @Test fun missingDocumentPortRejectsInsteadOfRenderingPartially() {
        val missingDocuments = allConnected - setOf(
            UiCapabilityId("ui_document.generate"), UiCapabilityId("ui_document.generate_local"),
            UiCapabilityId("ui_document.prepare_input"),
        )
        val result = LocalConversationPackGate.admit(
            NextConversationUiPack.manifest.id, missingDocuments, UiWindowClass.COMPACT, false,
        )
        assertTrue(result is UiPackAdmission.Rejected)
        result as UiPackAdmission.Rejected
        assertTrue(UiCapabilityId("ui_document.generate_local") in result.compatibility.missingCapabilities)
    }

    @Test fun neverSilentlySelectsAnotherPackForUnknownId() {
        assertTrue(LocalConversationPackGate.admit(
            "invalid", allConnected, UiWindowClass.COMPACT, false,
        ) is UiPackAdmission.UnknownPack)
    }

    @Test fun missingWorkApprovalCapabilityRejectsSlice() {
        val result = LocalConversationPackGate.admit(
            NextConversationUiPack.manifest.id,
            allConnected - UiCapabilityId("work.answer_approval"),
            UiWindowClass.COMPACT, false,
        )
        assertTrue(result is UiPackAdmission.Rejected)
    }
}

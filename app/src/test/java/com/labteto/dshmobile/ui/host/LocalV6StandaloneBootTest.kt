package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.presentation.LocalToolsUiFacade
import com.labteto.dshmobile.uicontract.SessionUiPort
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract that independent V6 boot cannot accidentally instantiate old business Shell. */
class LocalV6StandaloneBootTest {
    @Test fun verificationBuildCanRunCandidateButReleaseAdmissionRemainsUnsigned() {
        val actual = setOf(com.labteto.dshmobile.uicontract.UiCapabilityId("chat.send_text"))
        val seal = com.labteto.dshmobile.uicontract.UiCapabilityId("v6.migration.accepted")
        assertTrue(seal in v6RuntimeCapabilities(actual, true))
        assertFalse(seal in v6RuntimeCapabilities(actual, false))
        assertTrue(actual.all { it in v6RuntimeCapabilities(actual, true) })
    }

    @Test fun independentAppPreferenceCannotImplicitlyRedirectToLegacy() {
        assertTrue(isIndependentV6Selected("v6-application-migrating"))
        assertTrue(isIndependentV6Selected("legacy-v6"))
        assertTrue(isIndependentV6Selected(null))
        assertFalse(isIndependentV6Selected("v6-application-migrating-other"))
    }

    @Test fun standaloneHiltCompositionReferencesPortsNotLegacyViewModels() {
        val constructor = LocalV6StandaloneViewModel::class.java.declaredConstructors.single()
        val dependencies = constructor.parameterTypes.toSet()
        assertFalse(dependencies.any { it.simpleName == "LocalHarnessViewModel" })
        assertFalse(dependencies.any { it.simpleName == "LocalUiRuntime" })
        assertFalse(dependencies.any { it.simpleName == "LocalHarnessScreen" })
        assertTrue(LocalConversationUiPortComposition::class.java in dependencies)
        assertTrue(LocalToolsUiFacade::class.java in dependencies)
        assertTrue(SessionUiPort::class.java in dependencies)
        assertTrue(dependencies.any { it.simpleName == "WorkEvidenceUiPort" })
        assertTrue(dependencies.any { it.simpleName == "WorkReadUiPort" })
    }
}

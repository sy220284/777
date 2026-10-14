package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UiDocumentFormKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUiDocumentFormStateTest {
    @Test fun replacementRevisionDoesNotInheritStaleDraftAndOldRevisionIsEvicted() {
        var saved = arrayListOf<String>()
        val forms = LocalUiDocumentFormState(emptyList()) { saved = it }
        val original = UiDocumentFormKey("s", "d", 0)
        val updated = original.copy(revision = 1)
        val otherSession = UiDocumentFormKey("other", "d", 0)
        forms.update(original, "response", "private old draft")
        forms.update(otherSession, "response", "untouched")
        forms.update(updated, "response", "new revision")
        assertFalse(forms.values.value.containsKey(original))
        assertEquals("new revision", forms.values.value[updated]?.get("response"))
        assertEquals("untouched", forms.values.value[otherSession]?.get("response"))
        val restored = LocalUiDocumentFormState(saved) {}
        assertEquals(forms.values.value, restored.values.value)
    }

    @Test fun legacySnapshotIsReadableAndRewrittenAsVersionedState() {
        var saved = arrayListOf<String>()
        val forms = LocalUiDocumentFormState(listOf("s", "d", "reply", "legacy")) { saved = it }
        assertEquals("legacy", forms.values.value[UiDocumentFormKey("s", "d", 0)]?.get("reply"))
        forms.update(UiDocumentFormKey("s", "d", 0), "reply", "updated")
        assertEquals("ui-document-form-v2", saved.first())
        assertEquals("updated", LocalUiDocumentFormState(saved) {}.values.value[
            UiDocumentFormKey("s", "d", 0)]?.get("reply"))
    }

    @Test fun corruptedRevisionEntryDoesNotRegainAnActionOrMixWithAnotherSession() {
        val saved = listOf("ui-document-form-v2", "s", "d", "-2", "field", "value",
            "real", "doc", "2", "reply", "safe")
        val forms = LocalUiDocumentFormState(saved) {}
        assertEquals(1, forms.values.value.size)
        assertEquals("safe", forms.values.value[UiDocumentFormKey("real", "doc", 2)]?.get("reply"))
        assertTrue(UiDocumentFormKey("s", "d", 0) !in forms.values.value)
    }
}

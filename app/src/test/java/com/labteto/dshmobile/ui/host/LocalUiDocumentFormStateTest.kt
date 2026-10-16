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
    @Test fun oversizedDraftsAreRejectedWithoutErasingPreviouslySavedValues() {
        var saved = arrayListOf<String>()
        val forms = LocalUiDocumentFormState(emptyList()) { saved = it }
        val key = UiDocumentFormKey("session", "doc", 2)
        forms.update(key, "reply", "original")
        val stable = saved.toList()
        forms.update(key, "reply", "x".repeat(16_385))
        assertEquals("original", forms.values.value[key]?.get("reply"))
        assertEquals(stable, saved)
        forms.update(key.copy(revision = 3), "reply", "x".repeat(70_000))
        assertEquals("original", forms.values.value[key]?.get("reply"))
        assertFalse(forms.values.value.containsKey(key.copy(revision = 3)))
    }

    @Test fun restoredStateIsBoundedAndRejectsHugeValues() {
        val payload = listOf("ui-document-form-v2", "s", "d", "0", "secret",
            "x".repeat(100_000), "s", "other", "2", "ordinary", "valid")
        val forms = LocalUiDocumentFormState(payload) {}
        assertFalse(forms.values.value.containsKey(UiDocumentFormKey("s", "d", 0)))
        assertEquals("valid", forms.values.value[UiDocumentFormKey("s", "other", 2)]?.get("ordinary"))
    }

    @Test fun tooManyDocumentDraftsCannotGrowAndroidSavedStateIndefinitely() {
        var saved = arrayListOf<String>()
        val forms = LocalUiDocumentFormState(emptyList()) { saved = it }
        for (index in 0 until 60)
            forms.update(UiDocumentFormKey("session", "doc-$index", 0), "reply", "value")
        assertTrue(forms.values.value.size <= 24)
        assertTrue(saved.size <= 1 + 24 * 5)
    }

    @Test fun deletedDocumentDraftsCannotReturnFromSavedState() {
        var saved = arrayListOf<String>()
        val forms = LocalUiDocumentFormState(emptyList()) { saved = it }
        val deleted = UiDocumentFormKey("session", "private-document", 3)
        val other = UiDocumentFormKey("session", "other-document", 1)
        forms.update(deleted, "secret", "sensitive draft")
        forms.update(other, "ordinary", "keep")
        forms.forgetDocument(deleted)
        assertFalse(forms.values.value.containsKey(deleted))
        assertEquals("keep", forms.values.value[other]?.get("ordinary"))
        val restored = LocalUiDocumentFormState(saved) {}
        assertFalse(restored.values.value.containsKey(deleted))
        assertEquals("keep", restored.values.value[other]?.get("ordinary"))
    }

}

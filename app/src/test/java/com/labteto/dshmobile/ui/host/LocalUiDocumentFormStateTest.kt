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

    @Test fun sensitiveFieldsStayEditableButAreNotWrittenIntoSavedState() {
        var snapshot = arrayListOf<String>()
        val key = UiDocumentFormKey("session", "credentials", 2)
        val form = LocalUiDocumentFormState(emptyList()) { snapshot = it }
        form.update(key, "apiKey", "sk-example-private")
        form.update(key, "refresh_token", "sensitive-refresh")
        form.update(key, "password", "very-private")
        form.update(key, "ordinary", "safe-public")
        assertEquals("sk-example-private", form.values.value[key]?.get("apiKey"))
        assertEquals("sensitive-refresh", form.values.value[key]?.get("refresh_token"))
        assertEquals("very-private", form.values.value[key]?.get("password"))
        assertEquals("safe-public", form.values.value[key]?.get("ordinary"))
        assertFalse(snapshot.any { "private" in it || "sensitive-refresh" in it })
        val restored = LocalUiDocumentFormState(snapshot) {}
        assertEquals(mapOf("ordinary" to "safe-public"), restored.values.value[key])
    }

    @Test fun oldUntrustedSavedStateCannotReintroduceSensitiveCredentials() {
        var sanitized = arrayListOf<String>()
        val records = listOf("ui-document-form-v2",
            "s", "doc", "1", "clientSecret", "secret-restored",
            "s", "doc", "1", "secretary", "valid-name",
            "s", "doc", "1", "tokenizer", "valid-code",
            "s", "doc", "1", "access_token", "secret-access",
            "s", "doc", "1", "ordinary", "safe")
        val form = LocalUiDocumentFormState(records) { sanitized = it }
        assertFalse(sanitized.contains("secret-restored"))
        assertFalse(sanitized.contains("secret-access"))
        assertEquals("ui-document-form-v2", sanitized.first())
        assertEquals(mapOf("secretary" to "valid-name", "tokenizer" to "valid-code",
            "ordinary" to "safe"), form.values.value[UiDocumentFormKey("s", "doc", 1)])
        var scrubbedLegacy = arrayListOf<String>()
        val legacy = LocalUiDocumentFormState(
            listOf("s", "doc", "password", "old-password",
                "s", "doc", "ordinary", "safe")) { scrubbedLegacy = it }
        assertFalse(scrubbedLegacy.contains("old-password"))
        assertEquals(mapOf("ordinary" to "safe"),
            legacy.values.value[UiDocumentFormKey("s", "doc", 0)])
    }

    @Test fun disposingSensitiveFormClearsTransientValuesButKeepsOrdinarySavedDraft() {
        var saved = arrayListOf<String>()
        val key = UiDocumentFormKey("session", "form", 5)
        val forms = LocalUiDocumentFormState(emptyList()) { saved = it }
        forms.bindRetention(key, mapOf(
            "oneTime" to com.labteto.dshmobile.uicontract.UiFieldRetention.MEMORY_ONLY,
            "note" to com.labteto.dshmobile.uicontract.UiFieldRetention.SAVED_STATE,
        ))
        forms.update(key, "oneTime", "temporary-private")
        forms.update(key, "note", "keep-this")
        forms.releaseRetention(key)
        assertEquals(mapOf("note" to "keep-this"), forms.values.value[key])
        assertFalse(saved.contains("temporary-private"))
        assertEquals("keep-this", LocalUiDocumentFormState(saved) {}.values.value[key]?.get("note"))
        forms.bindRetention(key, mapOf(
            "oneTime" to com.labteto.dshmobile.uicontract.UiFieldRetention.MEMORY_ONLY,
            "note" to com.labteto.dshmobile.uicontract.UiFieldRetention.SAVED_STATE,
        ))
        assertFalse(forms.values.value[key].orEmpty().containsKey("oneTime"))
        forms.forgetSessions(setOf("session"))
        assertTrue(forms.values.value.isEmpty())
    }

    @Test fun explicitMemoryOnlyRetentionScrubsHistoricalValuesAndKeepsCurrentDraftTransient() {
        val key = UiDocumentFormKey("session", "typed-document", 4)
        var saved = arrayListOf<String>()
        val old = listOf("ui-document-form-v2", "session", "typed-document", "4",
            "oneTimeCode", "old-secret", "session", "typed-document", "4", "ordinary", "safe")
        val forms = LocalUiDocumentFormState(old) { saved = it }
        assertEquals("old-secret", forms.values.value[key]?.get("oneTimeCode"))
        forms.bindRetention(key, mapOf(
            "oneTimeCode" to com.labteto.dshmobile.uicontract.UiFieldRetention.MEMORY_ONLY,
            "ordinary" to com.labteto.dshmobile.uicontract.UiFieldRetention.SAVED_STATE,
        ))
        assertFalse(saved.contains("old-secret"))
        assertFalse(forms.values.value[key].orEmpty().containsKey("oneTimeCode"))
        forms.update(key, "oneTimeCode", "current-only")
        forms.update(key, "ordinary", "edited")
        assertEquals("current-only", forms.values.value[key]?.get("oneTimeCode"))
        assertFalse(saved.contains("current-only"))
        val restored = LocalUiDocumentFormState(saved) {}
        assertEquals(mapOf("ordinary" to "edited"), restored.values.value[key])
        forms.forgetDocument(key)
        assertFalse(forms.values.value.containsKey(key))
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

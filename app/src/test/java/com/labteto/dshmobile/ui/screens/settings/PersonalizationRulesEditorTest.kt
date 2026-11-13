package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.*
import org.junit.Test

class PersonalizationRulesEditorTest {
    @Test fun pendingWriteDoesNotCloseOrReportSuccessUntilConfirmed() {
        val editor = PersonalizationRulesEditor()
        var confirm: ((Boolean, String?) -> Unit)? = null
        var success = 0
        editor.open("on-disk")
        editor.edit("new rules")
        editor.save({ text, completion ->
            assertEquals("new rules", text)
            confirm = completion
        }, { success++ })
        assertTrue(editor.state.value.open)
        assertTrue(editor.state.value.saving)
        assertEquals(0, success)
        editor.edit("overwritten while pending")
        assertEquals("new rules", editor.state.value.text)
        confirm!!(true, null)
        assertFalse(editor.state.value.open)
        assertFalse(editor.state.value.saving)
        assertEquals(1, success)
    }

    @Test fun pageSwitchPreservesFailedDraftAndAllowsRetry() {
        val editor = PersonalizationRulesEditor()
        var completion: ((Boolean, String?) -> Unit)? = null
        var saved = 0
        editor.open("persisted")
        editor.edit("retry me")
        editor.save({ _, callback -> completion = callback }, { saved++ })
        // Page composition leaves and returns: ViewModel-owned editor is still here.
        assertEquals("retry me", editor.state.value.text)
        completion!!(false, "disk unavailable")
        assertTrue(editor.state.value.open)
        assertEquals("retry me", editor.state.value.text)
        assertEquals("disk unavailable", editor.state.value.error)
        editor.save({ text, callback ->
            assertEquals("retry me", text)
            completion = callback
        }, { saved++ })
        completion!!(true, null)
        assertFalse(editor.state.value.open)
        assertEquals(1, saved)
    }

    @Test fun cancelInvalidatesLateSuccessAndDoesNotCloseReopenedEditor() {
        val editor = PersonalizationRulesEditor()
        var stale: ((Boolean, String?) -> Unit)? = null
        var success = 0
        editor.open("old")
        editor.edit("cancelled")
        editor.save({ _, callback -> stale = callback }, { success++ })
        editor.cancel()
        editor.open("actual on disk")
        stale!!(true, null)
        assertEquals("actual on disk", editor.state.value.text)
        assertTrue(editor.state.value.open)
        assertFalse(editor.state.value.saving)
        assertEquals(0, success)
    }

    @Test fun cancelledFailureCannotOverwriteNewDraftOrShowUnrelatedError() {
        val editor = PersonalizationRulesEditor()
        var stale: ((Boolean, String?) -> Unit)? = null
        editor.open("committed")
        editor.save({ _, callback -> stale = callback }, {})
        editor.cancel()
        editor.open("committed")
        editor.edit("new edit")
        stale!!(false, "old unrelated failure")
        assertEquals("new edit", editor.state.value.text)
        assertNull(editor.state.value.error)
    }

    @Test fun supersededResultKeepsRetryableDraft() {
        val editor = PersonalizationRulesEditor()
        editor.open("old")
        editor.edit("new")
        editor.save({ _, confirm -> confirm(false, null) }, {})
        assertTrue(editor.state.value.open)
        assertEquals("new", editor.state.value.text)
        assertNotNull(editor.state.value.error)
        editor.edit("latest")
        assertNull(editor.state.value.error)
    }
}

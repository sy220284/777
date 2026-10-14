package com.labteto.dshmobile.local.presentation.document

import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiDocumentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUiDocumentCodecTest {
    private val source = UiDocumentSource("session", "message", 2, "profile", "project")
    private val budget = UiDocumentBudget(16, 4, 4096)
    private fun raw(node: String, action: String = "", extra: String = "") = """
        {"schemaVersion":1,"documentId":"document","revision":0,"title":"Form",
         "nodes":[$node],"actions":[$action],"fallbackText":"Plain text"$extra}
    """.trimIndent()
    private fun decode(value: String, pending: String? = null) =
        LocalUiDocumentCodec.decode(value, source, budget, emptySet(), pending)

    @Test fun sourceIdentityComesFromTheOwnerAndModelCannotReplaceIt() {
        val text = """{"id":"text","type":"text","text":"Hello"}"""
        assertEquals(source, decode(raw(text)).getOrThrow().source)
        assertTrue(decode(raw(text, extra = """, "source":{"sessionId":"other"}""")).isFailure)
    }

    @Test fun scriptsUrlsAndUnknownActionsNeverBecomeNativeControls() {
        assertTrue(decode(raw("""{"id":"script","type":"script","text":"run()"}""")).isFailure)
        assertTrue(decode(raw("""{"id":"text","type":"text","text":"Hello","url":"https://example.com"}""")).isFailure)
        assertTrue(decode(raw("""{"id":"button","type":"button","label":"Run","actionId":"run"}""",
            """{"id":"run","type":"execute_tool","tool":"shell"}""")).isFailure)
    }

    @Test fun approvalRequiresTheExactAdmittedOwnerWaiter() {
        val value = raw("""{"id":"button","type":"button","label":"Approve","actionId":"approval"}""",
            """{"id":"approval","type":"answer_approval","pendingId":"waiter","approved":true}""")
        assertTrue(decode(value).isFailure)
        assertTrue(decode(value, "old-waiter").isFailure)
        assertTrue(decode(value, "waiter").isSuccess)
    }

    @Test fun boundedTableRoundTripsAndRejectsMalformedRowsAndUnexpectedProperties() {
        val input = raw("""{"id":"table","type":"table","headers":["field","value"],"rows":[["phase","RUNNING"]]}""")
        val value = decode(input).getOrThrow()
        assertTrue(value.actions.isEmpty())
        assertEquals(value, decode(LocalUiDocumentCodec.encode(value).toString()).getOrThrow())
        assertTrue(decode(raw("""{"id":"table","type":"table","headers":["field","value"],"rows":[["bad"]]}""")).isFailure)
        assertTrue(decode(raw("""{"id":"table","type":"table","headers":["field"],"rows":[["x"]],"script":"execute"}""")).isFailure)
    }

    @Test fun oversizedInputFailsBeforeParsingRatherThanSilentlyTruncating() {
        val value = raw("""{"id":"text","type":"text","text":"${"x".repeat(4096)}"}""")
        val result = decode(value)
        assertTrue(result.isFailure)
        assertEquals("document_size", result.exceptionOrNull()?.message)
    }
}

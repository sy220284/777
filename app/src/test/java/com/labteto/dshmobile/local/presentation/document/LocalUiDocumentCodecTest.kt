package com.labteto.dshmobile.local.presentation.document

import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.initialFields
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

    @Test fun chartRoundTripsWithoutGrantingActionsOrAllowingExtraFields() {
        val chart = decode(raw("""{"id":"chart","type":"bar_chart","title":"工具调用","unit":"次","points":[{"label":"甲","value":3.0},{"label":"乙","value":0.0}]}""")).getOrThrow()
        assertTrue(chart.actions.isEmpty())
        assertEquals(chart, decode(LocalUiDocumentCodec.encode(chart).toString()).getOrThrow())
        assertTrue(decode(raw("""{"id":"chart","type":"bar_chart","title":"调用","unit":"","points":[{"label":"x","value":-1.0}]}""")).isFailure)
        assertTrue(decode(raw("""{"id":"chart","type":"bar_chart","title":"调用","unit":"","points":[{"label":"x","value":1.0,"action":"approve"}]}""")).isFailure)
    }

    @Test fun lineChartIsBoundedReadOnlyAndRoundTrips() {
        val chart = decode(raw("""{"id":"trend","type":"line_chart","title":"会话指标","unit":"次","points":[{"label":"周一","value":1.0},{"label":"周二","value":2.0}]}""")).getOrThrow()
        assertTrue(chart.actions.isEmpty())
        assertEquals(chart, decode(LocalUiDocumentCodec.encode(chart).toString()).getOrThrow())
        assertTrue(decode(raw("""{"id":"trend","type":"line_chart","title":"bad","unit":"次","points":[{"label":"a","value":-2.0}]}""")).isFailure)
        assertTrue(decode(raw("""{"id":"trend","type":"line_chart","title":"bad","unit":"次","points":[{"label":"a","value":2.0,"call":"tool"}]}""")).isFailure)
        assertTrue(decode(raw("""{"id":"trend","type":"line_chart","title":"bad","unit":"次","points":[{"label":"a","value":1.0},{"label":"a","value":2.0}]}""")).isFailure)
    }

    @Test fun sensitiveFieldRetentionRoundTripsAndOlderDocumentsRemainReadable() {
        val legacy = decode(raw("""{"id":"oneTimeCode","type":"field","label":"验证码","initial":"","required":true}"""))
            .getOrThrow()
        val oldField = legacy.nodes.single() as com.labteto.dshmobile.uicontract.UiDocumentNode.Field
        assertEquals(com.labteto.dshmobile.uicontract.UiFieldRetention.SAVED_STATE, oldField.retention)
        val strict = decode(raw("""{"id":"oneTimeCode","type":"field","label":"验证码","initial":"","required":true,"retention":"MEMORY_ONLY"}"""))
            .getOrThrow()
        val field = strict.nodes.single() as com.labteto.dshmobile.uicontract.UiDocumentNode.Field
        assertEquals(com.labteto.dshmobile.uicontract.UiFieldRetention.MEMORY_ONLY, field.retention)
        assertEquals(strict, decode(LocalUiDocumentCodec.encode(strict).toString()).getOrThrow())
        assertTrue(decode(raw("""{"id":"oneTimeCode","type":"field","label":"验证码","initial":"secret","required":true,"retention":"MEMORY_ONLY"}""")).isFailure)
        assertTrue(decode(raw("""{"id":"oneTimeCode","type":"field","label":"验证码","initial":"","required":true,"retention":"UNKNOWN"}""")).isFailure)
    }

    @Test fun oversizedInputFailsBeforeParsingRatherThanSilentlyTruncating() {
        val value = raw("""{"id":"text","type":"text","text":"${"x".repeat(4096)}"}""")
        val result = decode(value)
        assertTrue(result.isFailure)
        assertEquals("document_size", result.exceptionOrNull()?.message)
    }
    @Test fun nativeCalculationRoundTripAndLiveInputUpdates() {
        val input = raw("""{"id":"bill","type":"field","label":"账单","initial":"320","required":true},
          {"id":"people","type":"slider","label":"人数","minimum":1,"maximum":10,"step":1,"initial":4},
          {"id":"share","type":"calculated","label":"人均","leftId":"bill","rightId":"people",
           "operation":"DIVIDE","prefix":"¥","suffix":"","decimals":2}""")
        val doc = decode(input).getOrThrow()
        assertEquals(doc, decode(LocalUiDocumentCodec.encode(doc).toString()).getOrThrow())
        val calculated = doc.nodes.last() as com.labteto.dshmobile.uicontract.UiDocumentNode.Calculated
        val inputs = doc.initialFields()
        assertEquals("¥80.00", com.labteto.dshmobile.uicontract.calculateUiDocumentValue(calculated, inputs))
        assertEquals("¥64.00", com.labteto.dshmobile.uicontract.calculateUiDocumentValue(calculated, inputs + ("people" to "5.0")))
        assertTrue(com.labteto.dshmobile.uicontract.calculateUiDocumentValue(calculated, inputs + ("people" to "0")) == null)
        assertTrue(decode(input.replace("rightId\":\"people", "rightId\":\"missing")).isFailure)
        assertTrue(decode(input.replace("\"step\":1", "\"step\":0")).isFailure)
        assertTrue(decode(input.replace("\"maximum\":10", "\"maximum\":10000")).isFailure)
        assertTrue(decode(input.replace("\"operation\":\"DIVIDE", "\"operation\":\"EVAL")).isFailure)
    }

}

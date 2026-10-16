package com.labteto.dshmobile.uicontract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDocumentTest {
    private val source = UiDocumentSource("session", "message", 4, "profile", "project")
    private val budget = UiDocumentBudget(12, 3, 1024)
    private val scene = UiSceneId("route.project")
    private fun document(nodes: List<UiDocumentNode>, actions: List<UiDocumentAction>) =
        UiDocument(1, "document", source, 0, "Form", nodes, actions, "Text fallback")

    @Test fun typedInputsAndKnownNavigationAreAcceptedWithoutGrantingToolExecution() {
        val form = document(listOf(UiDocumentNode.Field("field", "Question", "", true, false),
            UiDocumentNode.Button("submit", "Prepare", "send"), UiDocumentNode.Button("navigate", "Project", "open")),
            listOf(UiDocumentAction.SendUserInput("send", "Question", listOf("field")),
                UiDocumentAction.OpenKnownScene("open", scene)))
        assertTrue(form.validate(budget, setOf(scene), null).valid)
        val badReference = form.copy(actions = listOf(UiDocumentAction.SendUserInput("send", "Question", listOf("missing"))))
        assertFalse(badReference.validate(budget, setOf(scene), null).valid)
        assertTrue(badReference.validate(budget, setOf(scene), null).problems.contains("input_reference"))
    }

    @Test fun inventedApprovalAndUnknownScenesCannotBecomeAvailableActions() {
        val approval = document(listOf(UiDocumentNode.Button("approve", "Approve", "approval")),
            listOf(UiDocumentAction.AnswerApproval("approval", "invented", true)))
        assertTrue(approval.validate(budget, setOf(scene), "real-pending").problems.contains("approval_identity"))
        val navigation = document(listOf(UiDocumentNode.Button("open", "Open", "open")),
            listOf(UiDocumentAction.OpenKnownScene("open", UiSceneId("javascript:execute"))))
        assertTrue(navigation.validate(budget, setOf(scene), null).problems.contains("unknown_scene"))
    }

    @Test fun reusedNodeIdsAndDeepTreesFailWithoutTruncatingTheDocument() {
        val repeated = document(listOf(UiDocumentNode.Text("same", "first"), UiDocumentNode.Text("same", "second")), emptyList())
        assertTrue(repeated.validate(budget, emptySet(), null).problems.contains("node_identity"))
        val deep = document(listOf(UiDocumentNode.Column("one", listOf(UiDocumentNode.Column("two",
            listOf(UiDocumentNode.Column("three", listOf(UiDocumentNode.Text("four", "text")))))))), emptyList())
        assertTrue(deep.validate(budget, emptySet(), null).problems.contains("depth_budget"))
        assertTrue(repeated.nodes.size == 2)
    }

    @Test fun tableShapeAndCellBudgetsAreValidated() {
        val table = document(listOf(UiDocumentNode.Table("facts", listOf("field", "value"),
            listOf(listOf("phase", "RUNNING"), listOf("queued", "2")))), emptyList())
        assertTrue(table.validate(budget, emptySet(), null).valid)
        assertTrue(table.copy(nodes = listOf(UiDocumentNode.Table("facts", listOf("field", "value"),
            listOf(listOf("only one"))))).validate(budget, emptySet(), null).problems.contains("table_shape"))
        assertTrue(table.copy(nodes = listOf(UiDocumentNode.Table("facts", listOf("field", "value"),
            List(30) { listOf("key", "value") }))).validate(budget, emptySet(), null).problems.contains("table_budget"))
        val combined = table.copy(nodes = List(3) { i ->
            UiDocumentNode.Table("table_$i", listOf("key", "value"), listOf(listOf("a", "b")))
        })
        assertTrue(combined.validate(budget, emptySet(), null).problems.contains("table_budget"))
    }

    @Test fun chartsRequireFiniteBoundedNumbersAndCannotGrantActions() {
        val chart = document(listOf(UiDocumentNode.BarChart("chart", "次数", "次",
            listOf(UiChartPoint("成功", 3.0), UiChartPoint("失败", 1.0)))), emptyList())
        assertTrue(chart.validate(budget, emptySet(), null).valid)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.0e13)) {
            assertTrue(chart.copy(nodes = listOf(UiDocumentNode.BarChart("chart", "次数", "",
                listOf(UiChartPoint("异常", invalid))))).validate(budget, emptySet(), null)
                .problems.contains("chart_values"))
        }
        assertTrue(chart.copy(nodes = listOf(UiDocumentNode.BarChart("chart", "次数", "",
            List(13) { UiChartPoint("p$it", it.toDouble()) }))).validate(budget, emptySet(), null)
            .problems.contains("chart_values"))
    }

    @Test fun lineChartsRejectNonfiniteNumbersAndBudgetOverruns() {
        val trend = document(listOf(UiDocumentNode.LineChart("trend", "观察窗口", "次",
            listOf(UiChartPoint("早", 1.0), UiChartPoint("晚", 3.0)))), emptyList())
        assertTrue(trend.validate(budget, emptySet(), null).valid)
        assertTrue(trend.copy(nodes = listOf(UiDocumentNode.LineChart("trend", "观察窗口", "次",
            listOf(UiChartPoint("坏", Double.NaN))))).validate(budget, emptySet(), null)
            .problems.contains("chart_values"))
        assertTrue(trend.copy(nodes = listOf(UiDocumentNode.LineChart("trend", "观察窗口", "次",
            List(13) { UiChartPoint("点$it", it.toDouble()) }))).validate(budget, emptySet(), null)
            .problems.contains("chart_budget"))
        assertTrue(trend.copy(nodes = listOf(UiDocumentNode.LineChart("trend", "观察窗口", "次",
            listOf(UiChartPoint("重复", 1.0), UiChartPoint("重复", 2.0))))).validate(budget, emptySet(), null)
            .problems.contains("chart_values"))
    }

    @Test fun patchesCannotCrossMessagesOrApplyToAnOldRevision() {
        val current = document(listOf(UiDocumentNode.Text("text", "old")), emptyList())
        val updated = current.copy(revision = 1, nodes = listOf(UiDocumentNode.Text("text", "new")))
        assertTrue(UiDocumentPatch("document", 0, updated).matches(current))
        assertFalse(UiDocumentPatch("document", 1, updated).matches(current))
        assertFalse(UiDocumentPatch("document", 0, updated.copy(source = source.copy(messageId = "other"))).matches(current))
    }

    @Test fun submittedValuesCannotBypassRequiredFieldsOrInventSelections() {
        val send = UiDocumentAction.SendUserInput("send", "Request", listOf("required", "choice", "enabled"))
        val form = document(listOf(
            UiDocumentNode.Disclosure("details", "Details", listOf(
                UiDocumentNode.Field("required", "Name", "", true, false),
                UiDocumentNode.Select("choice", "Choice", listOf("one", "two"), "one"),
                UiDocumentNode.Switch("enabled", "Enabled", false),
            )),
        ), listOf(send))
        assertTrue(form.invalidInputFields(send, form.initialFields()) == setOf("required"))
        val valid = form.initialFields() + ("required" to "中文姓名")
        assertTrue(form.invalidInputFields(send, valid).isEmpty())
        assertTrue(form.invalidInputFields(send, valid + mapOf("choice" to "invented", "enabled" to "yes")) ==
            setOf("choice", "enabled"))
    }

    @Test fun typedControlsRejectOversizedTextWithoutGrantingOwnerSend() {
        val action = UiDocumentAction.SendUserInput("send", "Request", listOf("short", "long"))
        val form = document(listOf(
            UiDocumentNode.Field("short", "Single line", "", true, false),
            UiDocumentNode.Field("long", "Long text", "", false, true),
        ), listOf(action))
        assertTrue(form.invalidInputFields(action, mapOf(
            "short" to "x".repeat(MAX_UI_DOCUMENT_SINGLE_LINE_CHARS + 1),
            "long" to "y",
        )) == setOf("short"))
        assertTrue(form.invalidInputFields(action, mapOf(
            "short" to "ok",
            "long" to "y".repeat(MAX_UI_DOCUMENT_MULTILINE_CHARS + 1),
        )) == setOf("long"))
        assertTrue(form.invalidInputFields(action, mapOf(
            "short" to "ok", "long" to "y".repeat(MAX_UI_DOCUMENT_MULTILINE_CHARS),
        )).isEmpty())
    }

    @Test fun combinedOutgoingMessageBudgetIsEnforcedBeforeInputConstruction() {
        val fields = (0..2).map { "long_$it" }
        val action = UiDocumentAction.SendUserInput("send", "Request", fields)
        val form = document(fields.map { id ->
            UiDocumentNode.Field(id, "Details", "", true, true)
        }, listOf(action))
        val huge = fields.associateWith { "x".repeat(MAX_UI_DOCUMENT_MULTILINE_CHARS) }
        val invalid = form.invalidInputFields(action, huge)
        assertTrue("__total_input_budget__" in invalid)
        assertTrue(fields.all { it in invalid })
        val safe = fields.associateWith { "x".repeat(500) }
        assertTrue(form.invalidInputFields(action, safe).isEmpty())
    }

    @Test fun actionsOnlyRequireTheirOwnInputsAndCannotReferenceMissingControls() {
        val form = document(listOf(
            UiDocumentNode.Field("optional", "Optional", "", false, true),
            UiDocumentNode.Field("required", "Required elsewhere", "", true, false),
        ), emptyList())
        val send = UiDocumentAction.SendUserInput("send", "Request", listOf("optional"))
        assertTrue(form.invalidInputFields(send, form.initialFields()).isEmpty())
        assertTrue(form.invalidInputFields(send.copy(fieldIds = listOf("missing")), emptyMap()) == setOf("missing"))
    }
}

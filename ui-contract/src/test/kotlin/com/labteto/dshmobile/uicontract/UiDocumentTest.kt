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

    @Test fun patchesCannotCrossMessagesOrApplyToAnOldRevision() {
        val current = document(listOf(UiDocumentNode.Text("text", "old")), emptyList())
        val updated = current.copy(revision = 1, nodes = listOf(UiDocumentNode.Text("text", "new")))
        assertTrue(UiDocumentPatch("document", 0, updated).matches(current))
        assertFalse(UiDocumentPatch("document", 1, updated).matches(current))
        assertFalse(UiDocumentPatch("document", 0, updated.copy(source = source.copy(messageId = "other"))).matches(current))
    }
}

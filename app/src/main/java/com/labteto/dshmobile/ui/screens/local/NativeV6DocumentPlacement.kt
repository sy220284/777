package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.UiDocumentSnapshot

/** Legacy Work progress is hidden; reply cards remain attached to the source turn. */
internal fun nativeV6DocumentsForMessage(
    messageId: String,
    role: String,
    workMode: Boolean,
    snapshots: List<UiDocumentSnapshot>,
): List<UiDocumentSnapshot> = if (!workMode) emptyList() else snapshots.filter { item ->
    item.document.source.messageId == messageId &&
        !item.document.documentId.startsWith("work-live:") &&
        (role == "assistant" || workMode && role == "user" &&
            item.document.source.workMode &&
            item.document.documentId == "reply-ui:${messageId}")
}

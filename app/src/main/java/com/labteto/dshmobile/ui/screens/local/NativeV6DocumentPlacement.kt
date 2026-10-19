package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.UiDocumentSnapshot

/** Work progress belongs to its originating user turn; reply cards belong to assistant rows. */
internal fun nativeV6DocumentsForMessage(
    messageId: String,
    role: String,
    workMode: Boolean,
    snapshots: List<UiDocumentSnapshot>,
): List<UiDocumentSnapshot> = snapshots.filter { item ->
    item.document.source.messageId == messageId &&
        (role == "assistant" || workMode && role == "user" &&
            item.document.source.workMode &&
            item.document.documentId in setOf("work-live:${messageId}", "reply-ui:${messageId}"))
}

package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.uicontract.UiSessionIdentity

/** One scalar projection for UI admission. Callers hold the foreground binding lock. */
internal fun LocalRuntimeStateStore.uiBindingIdentity(snapshot: LocalHarnessState): UiSessionIdentity? =
    snapshot.sessionId.takeIf { it.isNotBlank() }?.let {
        UiSessionIdentity(it, foregroundRunHandle.activationId,
            if (snapshot.usageMode == LocalUsageMode.CHAT) snapshot.chat.chatContext.generation else 0L,
            snapshot.modelState.modelSelection.activeProfile?.id, snapshot.projectId)
    }

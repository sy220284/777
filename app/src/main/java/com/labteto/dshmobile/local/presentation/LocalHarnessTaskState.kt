package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalGroupChatState
import com.labteto.dshmobile.local.chat.PersonaProfile

internal fun LocalHarnessState.toTaskUiState(): LocalHarnessTaskState =
    LocalHarnessTaskState(
        sessionId = sessionId,
        usageMode = usageMode,
        groupChat = groupChat,
        chatPersona = chatPersona,
    )


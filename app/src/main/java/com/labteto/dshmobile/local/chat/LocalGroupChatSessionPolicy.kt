package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.MIN_GROUP_CHAT_MEMBERS

/** Single authority for whether a persisted group session is valid for navigation/resume. */
internal fun LocalSessionSummary.isEstablishedGroupChatSession(): Boolean =
    usageMode == LocalUsageMode.CHAT &&
        chatMode == LocalChatMode.GROUP &&
        groupMemberCount >= MIN_GROUP_CHAT_MEMBERS

internal fun findEstablishedGroupChatSession(
    sessions: List<LocalSessionSummary>,
): LocalSessionSummary? = sessions.firstOrNull(LocalSessionSummary::isEstablishedGroupChatSession)

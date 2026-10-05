package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalSessionSummary

/** Single authority for whether a persisted group session is valid for navigation/resume. */
internal fun LocalSessionSummary.isEstablishedGroupChatSession(): Boolean =
    usageMode == LocalUsageMode.CHAT &&
        chatMode == LocalChatMode.GROUP.name &&
        groupMemberCount >= MIN_GROUP_CHAT_MEMBERS

internal fun findEstablishedGroupChatSession(
    sessions: List<LocalSessionSummary>,
): LocalSessionSummary? = sessions.firstOrNull(LocalSessionSummary::isEstablishedGroupChatSession)

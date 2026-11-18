package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.isLocalPdf
import com.labteto.dshmobile.local.attachment.parseLocalPdfPageSelection
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.chat.groupSpeakerLabel
import com.labteto.dshmobile.local.chat.groupSpeechDirectedInput

internal fun LocalImportedAttachment.canSelectPdfPages(): Boolean = isLocalPdf(name, mediaType)

internal fun parseComposerPdfPages(input: String): List<Int> = parseLocalPdfPageSelection(input)

internal fun composerGroupSpeakerLabel(member: LocalGroupChatMember, roster: List<LocalGroupChatMember>): String =
    groupSpeakerLabel(member, roster)

internal fun composerDirectedGroupInput(
    input: String,
    roster: List<LocalGroupChatMember>,
    member: LocalGroupChatMember?,
): String = groupSpeechDirectedInput(input, roster, member)

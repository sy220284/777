package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.MIN_GROUP_CHAT_MEMBERS

internal enum class LocalFeaturePage {
    HOME,
    PERSONA_GALLERY,
    DIARY,
    WORKSPACE,
    RUN_CENTER,
    TASKS,
    TOOLS,
    SETTINGS,
}

internal fun localFeatureCurrent(stack: List<String>): LocalFeaturePage =
    stack.lastOrNull()
        ?.let { runCatching { LocalFeaturePage.valueOf(it) }.getOrNull() }
        ?: LocalFeaturePage.HOME

internal fun localFeaturePush(
    stack: List<String>,
    page: LocalFeaturePage,
): List<String> {
    if (localFeatureCurrent(stack) == page) return stack
    return (stack.ifEmpty { listOf(LocalFeaturePage.HOME.name) } + page.name).takeLast(12)
}

internal fun localFeaturePop(stack: List<String>): List<String> =
    if (stack.size <= 1) listOf(LocalFeaturePage.HOME.name) else stack.dropLast(1)

internal fun localFeatureHome(): List<String> = listOf(LocalFeaturePage.HOME.name)

internal fun hasEstablishedGroupChat(sessions: List<LocalSessionSummary>): Boolean =
    sessions.any {
        it.usageMode == LocalUsageMode.CHAT &&
            it.chatMode == LocalChatMode.GROUP &&
            it.groupMemberCount >= MIN_GROUP_CHAT_MEMBERS
    }

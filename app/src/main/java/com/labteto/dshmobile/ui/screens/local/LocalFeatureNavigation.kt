package com.labteto.dshmobile.ui.screens.local

import androidx.activity.BackEventCompat
import com.labteto.dshmobile.local.chat.findEstablishedGroupChatSession
import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.feature.LocalFeatureRoute
import com.labteto.dshmobile.local.session.LocalSessionSummary

internal typealias LocalFeaturePage = LocalFeatureRoute

internal fun localFeatureCurrent(stack: List<String>): LocalFeaturePage =
    LocalFeatureCatalog.resolve(stack.lastOrNull()) ?: LocalFeaturePage.HOME

internal fun localFeaturePush(
    stack: List<String>,
    page: LocalFeaturePage,
): List<String> {
    if (localFeatureCurrent(stack) == page) return stack
    return (stack.ifEmpty { listOf(LocalFeaturePage.HOME.name) } + page.name).takeLast(12)
}

internal data class LocalFeatureDrawerOpenResult(
    val originStack: List<String>,
    val stack: List<String>,
)

internal enum class LocalFeatureBackAction {
    OPEN_DRAWER,
    POP_FEATURE,
}

internal fun localFeatureBackAction(swipeEdge: Int?): LocalFeatureBackAction =
    if (swipeEdge == BackEventCompat.EDGE_LEFT) {
        LocalFeatureBackAction.OPEN_DRAWER
    } else {
        LocalFeatureBackAction.POP_FEATURE
    }

internal fun localFeatureOpenFromDrawer(
    stack: List<String>,
    originStack: List<String>?,
    page: LocalFeaturePage,
): LocalFeatureDrawerOpenResult {
    val origin = (originStack ?: stack).ifEmpty { localFeatureHome() }
    return LocalFeatureDrawerOpenResult(
        originStack = origin,
        stack = localFeaturePush(origin, page),
    )
}

internal fun localFeaturePop(stack: List<String>): List<String> =
    if (stack.size <= 1) listOf(LocalFeaturePage.HOME.name) else stack.dropLast(1)

internal fun localFeatureHome(): List<String> = listOf(LocalFeaturePage.HOME.name)

internal fun establishedGroupChatSessionId(sessions: List<LocalSessionSummary>): String? =
    findEstablishedGroupChatSession(sessions)?.id

internal fun hasEstablishedGroupChat(sessions: List<LocalSessionSummary>): Boolean =
    establishedGroupChatSessionId(sessions) != null

internal fun acceptLocalSessionNavigation(
    currentSessionId: String,
    targetSessionId: String,
    sessions: List<LocalSessionSummary>,
    switchSession: (String) -> Boolean,
): Boolean {
    if (targetSessionId == currentSessionId) return true
    if (sessions.none { it.id == targetSessionId }) return false
    return switchSession(targetSessionId)
}

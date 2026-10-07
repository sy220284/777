package com.labteto.dshmobile.ui.screens.local

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
    POP_FEATURE,
}

/**
 * Android reserves inward swipes from both screen edges for system Back.
 *
 * Drawer opening is owned by the content-area gesture of [androidx.compose.material3.ModalNavigationDrawer],
 * so feature pages must never reinterpret a system-edge Back gesture as "open drawer".
 */
@Suppress("UNUSED_PARAMETER")
internal fun localFeatureProductBackAction(swipeEdge: Int?): LocalFeatureBackAction =
    LocalFeatureBackAction.POP_FEATURE

internal fun localFeatureRestoreOwnedPage(page: LocalFeaturePage): LocalFeaturePage = page

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

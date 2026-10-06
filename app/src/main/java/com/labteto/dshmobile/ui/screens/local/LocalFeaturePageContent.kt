package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId

internal enum class LocalFeatureDrawerEntry {
    WORKSPACE,
    RUN_CENTER,
    GROUP_CHAT,
    PERSONA_GALLERY,
    PERSONA_GALLERY_CONTINUE,
    DIARY,
    TASKS,
    TOOLS,
    SETTINGS,
}

/** Feature-owned UI/navigation contribution. Shell only hosts and executes the returned actions. */
internal data class LocalFeatureUiContribution(
    val moduleId: LocalFeatureModuleId,
    val drawerActions: Map<LocalFeatureDrawerEntry, () -> Unit> = emptyMap(),
    val backAction: (LocalFeaturePage, Int?) -> LocalFeatureBackAction? = { _, _ -> null },
    val restorePage: (LocalFeaturePage) -> LocalFeaturePage? = { null },
    val content: @Composable (LocalFeaturePage) -> Unit,
)

internal fun localFeatureContributionFor(
    page: LocalFeaturePage,
    contributions: List<LocalFeatureUiContribution>,
): LocalFeatureUiContribution {
    val owner = LocalFeatureCatalog.ownerOf(page)
    return contributions.singleOrNull { it.moduleId == owner }
        ?: error("Missing UI contribution for feature page: $page / $owner")
}

internal fun localFeatureDrawerAction(
    entry: LocalFeatureDrawerEntry,
    contributions: List<LocalFeatureUiContribution>,
): (() -> Unit)? {
    val actions = contributions.mapNotNull { it.drawerActions[entry] }
    check(actions.size <= 1) { "Drawer entry has multiple Feature owners: $entry" }
    return actions.singleOrNull()
}

internal fun localFeatureOwnedBackAction(
    page: LocalFeaturePage,
    swipeEdge: Int?,
    contributions: List<LocalFeatureUiContribution>,
): LocalFeatureBackAction? = localFeatureContributionFor(page, contributions).backAction(page, swipeEdge)

internal fun localFeatureRestoreStack(
    stack: List<String>,
    contributions: List<LocalFeatureUiContribution>,
): List<String> {
    val restored = stack.mapNotNull(LocalFeatureCatalog::resolve)
        .mapNotNull { page -> localFeatureContributionFor(page, contributions).restorePage(page) }
        .map(LocalFeaturePage::name)
        .takeLast(12)
    return if (restored.firstOrNull() == LocalFeaturePage.HOME.name) restored else localFeatureHome() + restored
}

@Composable
internal fun LocalFeaturePageContent(
    page: LocalFeaturePage,
    contributions: List<LocalFeatureUiContribution>,
) {
    val contribution = localFeatureContributionFor(page, contributions)
    Box(Modifier.fillMaxSize()) {
        contribution.content(page)
    }
}

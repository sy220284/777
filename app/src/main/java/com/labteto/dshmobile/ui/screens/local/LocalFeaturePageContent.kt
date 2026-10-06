package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId

/** Feature-owned UI contribution. The Shell only resolves the immutable catalog owner. */
internal data class LocalFeatureUiContribution(
    val moduleId: LocalFeatureModuleId,
    val content: @Composable (LocalFeaturePage) -> Unit,
)

@Composable
internal fun LocalFeaturePageContent(
    page: LocalFeaturePage,
    contributions: List<LocalFeatureUiContribution>,
) {
    val owner = LocalFeatureCatalog.ownerOf(page)
    val contribution = contributions.singleOrNull { it.moduleId == owner }
        ?: error("Missing UI contribution for feature page: $page / $owner")
    Box(Modifier.fillMaxSize()) {
        contribution.content(page)
    }
}

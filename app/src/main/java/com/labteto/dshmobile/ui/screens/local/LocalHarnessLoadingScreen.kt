package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsPageLoadingState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.rootSurface

/** Stable local-runtime bootstrap surface; keeps configuration form responsibility separate. */
@Composable
internal fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(DsTheme.colors.rootSurface())) {
        DsPageLoadingState(
            icon = FeatherIcons.Activity,
            label = stringResource(R.string.local_harness_loading),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

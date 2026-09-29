package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsAnimations

/**
 * Shared vertical reveal used by compact controls, search fields and diagnostic details.
 *
 * Motion stays in the Design System so feature screens do not invent their own timing or combine
 * multiple parent/child size animations that make expansion feel sticky.
 */
@Composable
fun DsExpandableColumn(
    visible: Boolean,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
        exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
    ) {
        Column(
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

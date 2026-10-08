package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsAnimations

/** A deeper page enters from the right; returning restores that page's saved UI state. */
@Composable
fun <T> DsHierarchyPage(
    page: T,
    pageKey: (T) -> String,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val pages = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        contentKey = pageKey,
        transitionSpec = {
            val direction = when {
                depth(targetState) > depth(initialState) -> 1
                depth(targetState) < depth(initialState) -> -1
                else -> 0
            }
            ((slideInHorizontally(DsAnimations.featurePageForward) { it * direction / 9 } +
                fadeIn(DsAnimations.featurePageEnterFade)) togetherWith
                (slideOutHorizontally(DsAnimations.featurePageBackward) { -it * direction / 18 } +
                    fadeOut(DsAnimations.featurePageExitFade))).using(SizeTransform(clip = false))
        },
        label = "hierarchyPage",
    ) { shownPage ->
        pages.SaveableStateProvider(pageKey(shownPage)) { content(shownPage) }
    }
}

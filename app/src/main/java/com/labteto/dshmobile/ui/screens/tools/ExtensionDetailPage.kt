package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.shenyan.SyFullScreenDialog
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.shenyan.designsystem.foundation.LocalSyColors

/**
 * A dedicated, full-screen detail destination. The list only selects a skill;
 * install, OAuth, dependency review and advanced actions live on this page.
 */
@Composable
internal fun ExtensionDetailPage(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalSyColors.current
    val scroll = rememberScrollState()
    val transitionDistance = with(LocalDensity.current) { 64.dp.toPx() }
    val titleProgress by remember(scroll, transitionDistance) {
        derivedStateOf { (scroll.value / transitionDistance).coerceIn(0f, 1f) }
    }
    SyFullScreenDialog(onDismiss = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = colors.surfaceSolid) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                SyTopBar(title = title, onBack = onDismiss,
                    backContentDescription = "返回技能列表", titleAlpha = titleProgress)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text,
                        modifier = Modifier.fillMaxWidth().alpha(1f - titleProgress)
                            .then(if (titleProgress >= .5f) Modifier.clearAndSetSemantics { } else Modifier))
                    content()
                }
                HorizontalDivider(color = colors.border)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                    content = footer,
                )
            }
        }
    }
}

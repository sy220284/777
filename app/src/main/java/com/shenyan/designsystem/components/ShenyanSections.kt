package com.shenyan.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** A section is identified by typography and space, not another decorative card. */
@Composable
fun SySectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = LocalSyColors.current
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
            Text(title, modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = colors.text)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
    // Headers remain text-only even with custom wallpaper: do not reintroduce horizontal glass strips.
    androidx.compose.foundation.layout.Box(modifier) { body() }
}

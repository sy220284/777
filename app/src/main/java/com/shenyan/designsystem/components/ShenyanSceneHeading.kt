package com.shenyan.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors

/** Scene introduction owns only visual hierarchy. No state, counters or feature actions. */
@Composable
fun SySceneHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val colors = LocalSyColors.current
    val compact = LocalConfiguration.current.screenHeightDp < 700 || LocalDensity.current.fontScale >= 1.3f
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, modifier = Modifier.semantics { heading() }, color = colors.text,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayLarge)
        if (!compact && !subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

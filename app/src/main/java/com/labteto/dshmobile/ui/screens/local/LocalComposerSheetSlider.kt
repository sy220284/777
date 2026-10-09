package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.labteto.dshmobile.ui.components.rememberHapticTickFeedback
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlin.math.roundToInt

/** Touch-friendly, discrete menu slider; commits only after the user finishes dragging. */
@Composable
internal fun LocalComposerSheetSlider(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    hint: String? = null,
    onSelect: (Int) -> Unit,
) {
    require(labels.size >= 2)
    val colors = DsTheme.colors
    var draft by remember(selectedIndex, labels) { mutableFloatStateOf(selectedIndex.toFloat()) }
    val tick = rememberHapticTickFeedback(selectedIndex.coerceIn(labels.indices))
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
            Text(labels[selectedIndex.coerceIn(labels.indices)], style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary)
        }
        Slider(
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = labels[draft.roundToInt().coerceIn(labels.indices)]
            },
            value = draft,
            enabled = enabled,
            onValueChange = { next ->
                draft = next
                tick(next.roundToInt().coerceIn(labels.indices))
            },
            onValueChangeFinished = {
                onSelect(draft.roundToInt().coerceIn(labels.indices))
            },
            valueRange = 0f..labels.lastIndex.toFloat(),
            steps = labels.size - 2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { label ->
                Text(label, style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
            }
        }
        if (hint != null) Text(hint, style = DsType.caption11.withReadingWeight(),
            color = colors.labelSecondary)
    }
}

package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Centered empty state: hero headline, optional subtitle, a mono product pill, and suggestion
 * chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyHero(
    headline: String,
    subtitle: String?,
    chips: List<String> = emptyList(),
    onChipClick: (String) -> Unit = {},
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            headline,
            style = DsType.hero26,
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        subtitle?.let {
            Text(
                it,
                style = DsType.base16,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(R.string.app_name),
            style = DsType.xsmall12.copy(fontFamily = DsType.codeFont, color = colors.accent),
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(colors.accentTertiary)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    DsPill(text = chip, onClick = { onChipClick(chip) })
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EmptyHeroPreview() {
    DshTheme {
        EmptyHero(
            headline = "Nothing running yet",
            subtitle = "Ask the harness anything, or pick a suggestion below.",
            chips = listOf("Summarize this repo", "Run the test suite", "Explain a diff"),
            onChipClick = {},
        )
    }
}

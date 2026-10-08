package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.DshTheme

/** Section title row with an optional right-aligned accent action. */
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    titleTextStyle: TextStyle? = null,
) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = (titleTextStyle ?: DsType.std14Strong).withReadingWeight(),
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = DsSpacing.touchTarget)
                    .clip(DsShapes.row)
                    .then(
                        if (onAction != null) {
                            Modifier.clickable(role = Role.Button, onClick = onAction)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = DsSpacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    action,
                    style = DsType.caption11Strong.withReadingWeight(),
                    color = colors.accent,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SectionHeaderPreview() {
    DshTheme {
        SectionHeader(
            title = "Tool calls",
            action = "Clear",
            onAction = {},
        )
    }
}

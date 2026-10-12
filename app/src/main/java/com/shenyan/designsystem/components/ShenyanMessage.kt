package com.shenyan.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** Short messages hug the sender edge; long messages stay readable on tablets. */
@Composable
fun SyUserBubble(text: String, modifier: Modifier = Modifier,
    background: Color = LocalSyColors.current.selected,
    border: Color = LocalSyColors.current.border,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    val p = LocalSyColors.current
    val shape = RoundedCornerShape(SyMetric.radiusCard)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(text, style = textStyle, color = p.text,
            modifier = Modifier.widthIn(max = minOf(525.dp, maxWidth * .82f))
                .background(background, shape).border(1.dp, border, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

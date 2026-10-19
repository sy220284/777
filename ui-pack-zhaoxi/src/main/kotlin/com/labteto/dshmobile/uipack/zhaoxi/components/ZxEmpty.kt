package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/** Calm reusable empty state; never implies a capability or a successful operation. */
@Composable
fun ZxEmpty(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    iconName: String = "sparkle",
) {
    val c = ZhaoxiTheme.colors
    val hasAction = !actionLabel.isNullOrBlank() && onAction != null
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (hasAction) Modifier.background(c.card, shape)
                .border(1.dp, c.line, shape) else Modifier)
            .padding(horizontal = 20.dp, vertical = if (hasAction) 24.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).background(c.bg2, CircleShape),
            contentAlignment = Alignment.Center) {
            ZxIcon(iconName, Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = c.ink, textAlign = TextAlign.Center)
        if (body.isNotBlank()) Text(body, fontSize = 12.sp, color = c.ink2,
            lineHeight = 19.sp, textAlign = TextAlign.Center)
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(8.dp))
            ZxButton(actionLabel, onAction, variant = ZxButtonVariant.Outline)
        }
    }
}

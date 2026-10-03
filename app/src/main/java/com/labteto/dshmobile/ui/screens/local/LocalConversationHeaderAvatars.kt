package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.LocalGroupChatMember
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GroupChatMemberAvatar(member: LocalGroupChatMember, active: Boolean) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(null, member.portraitPath) {
        value = withContext(Dispatchers.IO) { decodePersonaPortraitBitmap(member.portraitPath, maxEdgePx = 256) }
    }
    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = if (active) colors.characterAccentTertiary else Color.Transparent,
        border = if (active) BorderStroke(1.dp, colors.characterAccent) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = member.displayName,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Text(
                    member.displayName.trim().take(1).ifBlank { "·" },
                    style = DsType.caption11.withReadingWeight(),
                    color = if (active) colors.characterAccent else colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun LocalPersonaHeaderAvatar(name: String, portraitPath: String) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(null, portraitPath) {
        value = withContext(Dispatchers.IO) { decodePersonaPortraitBitmap(portraitPath, maxEdgePx = 256) }
    }
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = colors.characterAccentTertiary,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Text(
                    name.trim().take(1).ifBlank { "·" },
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.characterAccent,
                )
            }
        }
    }
}


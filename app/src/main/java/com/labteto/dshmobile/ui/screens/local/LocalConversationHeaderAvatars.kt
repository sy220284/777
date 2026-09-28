package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.LocalGroupChatMember
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GroupChatMemberAvatar(member: LocalGroupChatMember, active: Boolean) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(null, member.portraitPath) {
        value = withContext(Dispatchers.IO) { decodeLocalPersonaHeaderPortrait(member.portraitPath) }
    }
    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent,
        border = if (active) BorderStroke(1.dp, colors.accent) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = member.displayName,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    member.displayName.trim().take(1).ifBlank { "·" },
                    style = DsType.caption11,
                    color = if (active) colors.accent else colors.labelSecondary,
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
        value = withContext(Dispatchers.IO) { decodeLocalPersonaHeaderPortrait(portraitPath) }
    }
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = colors.accentTertiary,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    name.trim().take(1).ifBlank { "·" },
                    style = DsType.small13Strong,
                    color = colors.accent,
                )
            }
        }
    }
}

private fun decodeLocalPersonaHeaderPortrait(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}

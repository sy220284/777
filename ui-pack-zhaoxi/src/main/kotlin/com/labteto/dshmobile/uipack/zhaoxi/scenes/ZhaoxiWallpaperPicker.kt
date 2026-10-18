package com.labteto.dshmobile.uipack.zhaoxi.scenes

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.components.zxPressSurface
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uipack.zhaoxi.theme.zhaoxiWallpaperPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** The ten previews are actual packaged artwork, decoded as small bitmaps off the UI thread. */
@Composable
internal fun ZhaoxiWallpaperPicker(selectedPreset: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val previews by produceState<Map<String, ImageBitmap>>(emptyMap(), context) {
        value = withContext(Dispatchers.IO) {
            buildMap {
                context.assets.open("e818_wallpapers.zip").use { source ->
                    ZipInputStream(source).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (zhaoxiWallpaperPresets.none { it.key == entry.name }) continue
                            val bytes = zip.readBytes()
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sample = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 260) sample *= 2
                            BitmapFactory.decodeByteArray(
                                bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inSampleSize = sample },
                            )?.let { put(entry.name, it.asImageBitmap()) }
                        }
                    }
                }
            }
        }
    }
    val c = ZhaoxiTheme.colors
    val entries = listOf("auto" to "跟随主题") + zhaoxiWallpaperPresets.map { it.key to it.label }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (key, name) ->
            val active = selectedPreset == key
            val interaction = remember(key) { MutableInteractionSource() }
            Column(
                Modifier.width(102.dp)
                    .zxPressSurface(interaction)
                    .clip(RoundedCornerShape(14.dp))
                    .border(BorderStroke(if (active) 2.dp else 1.dp, if (active) c.accent else c.line),
                        RoundedCornerShape(14.dp))
                    .clickable(interactionSource = interaction, indication = LocalIndication.current,
                        role = Role.RadioButton) { onSelect(key) }
                    .semantics { selected = active }
                    .padding(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(122.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.verticalGradient(listOf(c.micaA, c.bg2))),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = previews[key]
                    if (preview != null) Image(
                        bitmap = preview, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(122.dp),
                    )
                    else if (key == "auto") Text("自动", color = c.ink, fontSize = 17.sp)
                    else Text("加载中", color = c.ink2, fontSize = 11.sp)
                    if (active) Text("✓",
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(CircleShape).background(c.card).padding(horizontal = 5.dp, vertical = 1.dp),
                        color = c.accentInk, fontSize = 12.sp)
                }
                Text(name, color = if (active) c.accentInk else c.ink2,
                    fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

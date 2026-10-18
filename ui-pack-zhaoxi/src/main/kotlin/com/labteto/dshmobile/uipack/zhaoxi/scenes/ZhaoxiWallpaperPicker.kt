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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.theme.zhaoxiWallpaperPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** The ten previews are actual packaged artwork, decoded as small bitmaps off the UI thread. */
@Composable
internal fun ZhaoxiWallpaperPicker(
    selectedPreset: String,
    saving: Boolean,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var previewRevision by remember { mutableIntStateOf(0) }
    val previewResult by produceState<Result<Map<String, ImageBitmap>>?>(null, context, previewRevision) {
        value = try {
            Result.success(withContext(Dispatchers.IO) {
            buildMap {
                context.assets.open("e818_wallpapers.zip").use { source ->
                    ZipInputStream(source).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (zhaoxiWallpaperPresets.none { it.key == entry.name }) continue
                            val bytes = zip.readBytes()
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            // Match the actual screen-density thumbnail width. The previous
                            // fixed 260px long-edge limit reduced 828px artwork to ~104px wide.
                            val requiredWidth = (102f * context.resources.displayMetrics.density).toInt()
                            var sample = 1
                            while (bounds.outWidth / (sample * 2) >= requiredWidth) sample *= 2
                            BitmapFactory.decodeByteArray(
                                bytes, 0, bytes.size,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                                },
                            )?.let { put(entry.name, it.asImageBitmap()) }
                        }
                    }
                }
            }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure<Map<String, ImageBitmap>>(error)
        }
    }
    val previews = previewResult?.getOrNull().orEmpty()
    val previewError = previewResult?.isFailure == true
    val c = ZhaoxiTheme.colors
    val entries = listOf("auto" to "跟随主题") + zhaoxiWallpaperPresets.map { it.key to it.label }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.first }) { (key, name) ->
            val active = selectedPreset == key
            val interaction = remember(key) { MutableInteractionSource() }
            Column(
                Modifier.width(102.dp)
                    .zxPressSurface(interaction)
                    .clip(RoundedCornerShape(14.dp))
                    .border(BorderStroke(if (active) 2.dp else 1.dp, if (active) c.accent else c.line),
                        RoundedCornerShape(14.dp))
                    .clickable(enabled = !saving, interactionSource = interaction,
                        indication = LocalIndication.current, role = Role.RadioButton) { onSelect(key) }
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
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        modifier = Modifier.fillMaxWidth().height(122.dp),
                    )
                    else if (key == "auto") Text("自动", color = c.ink, fontSize = 17.sp)
                    else Text(
                        when {
                            previewResult == null -> "加载中"
                            previewError -> "预览失败"
                            else -> "暂无预览"
                        }, color = c.ink2, fontSize = 11.sp,
                    )
                    if (active) Text("✓",
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(CircleShape).background(c.card).padding(horizontal = 5.dp, vertical = 1.dp),
                        color = c.accentInk, fontSize = 12.sp)
                }
                Text(name, color = if (active) c.accentInk else c.ink2,
                    fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp,
                    modifier = Modifier.heightIn(min = 32.dp))
            }
        }
    }
    if (previewError) {
        ZxChip("壁纸预览读取失败，重新加载", false) { previewRevision++ }
    }
}

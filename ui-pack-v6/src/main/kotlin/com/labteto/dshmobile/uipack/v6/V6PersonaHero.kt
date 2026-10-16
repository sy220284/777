package com.labteto.dshmobile.uipack.v6

import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.PersonaPortraitUiPort
import com.labteto.dshmobile.uicontract.UiPersonaGalleryEntry
import com.labteto.dshmobile.uicontract.UiPersonaPortraitPreviewReceipt
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyEntrance
import com.shenyan.designsystem.foundation.*
import androidx.compose.ui.graphics.graphicsLayer
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Transient Owner-provided portrait; never persist image bytes or assume a filesystem path. */
@Composable
internal fun V6PersonaHero(person: UiPersonaGalleryEntry, portraits: PersonaPortraitUiPort?) {
    var portrait by remember(person.id, portraits) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(person.id, portraits) { mutableStateOf<String?>(null) }
    var loading by remember(person.id, portraits) { mutableStateOf(portraits != null) }
    var retry by remember(person.id, portraits) { mutableIntStateOf(0) }
    LaunchedEffect(person.id, portraits, retry) {
        val owner = portraits ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val preview = withContext(Dispatchers.IO) {
                when (val result = owner.preview(person.id)) {
                    is UiPersonaPortraitPreviewReceipt.Ready -> ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(ByteBuffer.wrap(result.bytes))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        var sample = 1
                        while (maxOf(info.size.width, info.size.height) / sample > 1600) sample *= 2
                        decoder.setTargetSampleSize(sample)
                    }.asImageBitmap() to null
                    UiPersonaPortraitPreviewReceipt.Missing -> null to null
                    is UiPersonaPortraitPreviewReceipt.Failed ->
                        null to (result.message ?: "人物图片暂时无法加载")
                }
            }
            portrait = preview.first
            error = preview.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "人物图片暂时无法加载" }
        finally { loading = false }
    }
    val colors = LocalSyColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val image = portrait
        val reduced = LocalSyReduceMotion.current
        val tempo = LocalSyMotionTempo.current
        var response by remember(person.id) { mutableIntStateOf(0) }
        val scale = remember(person.id) { Animatable(1f) }
        LaunchedEffect(person.id, response, reduced, tempo) {
            if (reduced || response == 0) scale.snapTo(1f)
            else {
                scale.animateTo(1.025f, tween(SyMotion.duration(SyMotion.selectMs, false, tempo), easing = SyMotion.ease))
                scale.animateTo(1f, tween(SyMotion.duration(SyMotion.releaseMs, false, tempo), easing = SyMotion.releaseEase))
            }
        }
        // Same geometry before/after decoding; the portrait arrives without moving the name.
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(SyMetric.radiusCard))
            .background(colors.selected).clickable(onClickLabel = "轻触人物图片", onClick = { response++ }),
            contentAlignment = Alignment.Center) {
            if (image == null) Text(person.name.take(1), style = MaterialTheme.typography.headlineLarge, color = colors.text)
            if (image != null) SyEntrance("portrait:${person.id}", durationMs = SyMotion.portraitRevealMs) {
                Image(image, contentDescription = person.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale.value; scaleY = scale.value
                    }, contentScale = ContentScale.Fit)
            }
        }
        SyEntrance("name:${person.id}", order = 1) {
            Text(person.name, style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }
        SyEntrance("stories:${person.id}", order = 2) {
            Text("${person.stories.size} 段故事 · ${person.stories.sumOf { it.messageCount }} 条共同记录",
                style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        if (loading) Text("正在加载人物图片", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            SyButton("重新加载图片", { retry++ }, style = SyButtonStyle.GHOST, enabled = !loading)
        }
    }
}

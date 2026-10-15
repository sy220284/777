package com.labteto.dshmobile.uipack.next

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.PersonaPortraitUiPort
import com.labteto.dshmobile.uicontract.UiPersonaPortraitPreviewReceipt
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private paths stay in Chat; only transient, bounded bytes reach this independent UI. */
@Composable
internal fun NextPersonaPortraitPreview(port: PersonaPortraitUiPort, galleryId: String, close: () -> Unit) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val missing = stringResource(R.string.next_gallery_portrait_missing)
    val invalid = stringResource(R.string.next_gallery_portrait_invalid)
    val title = stringResource(R.string.next_gallery_portrait_preview)
    LaunchedEffect(port, galleryId, refresh) {
        loading = true; image = null; feedback = null
        try {
            val decoded = withContext(Dispatchers.IO) {
                when (val result = port.preview(galleryId)) {
                    is UiPersonaPortraitPreviewReceipt.Ready -> {
                        // Match the existing V6 portrait preview's 1600px sampling budget.
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(result.bytes))) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            var sample = 1
                            val longest = maxOf(info.size.width, info.size.height)
                            while (longest / sample > 1600) sample *= 2
                            if (sample > 1) decoder.setTargetSampleSize(sample)
                        }.asImageBitmap() to null
                    }
                    UiPersonaPortraitPreviewReceipt.Missing -> null to missing
                    is UiPersonaPortraitPreviewReceipt.Failed -> null to (result.message ?: invalid)
                }
            }
            image = decoded.first; feedback = decoded.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback = error.message ?: invalid }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = {
            if (loading) Text(stringResource(R.string.next_loading))
            feedback?.let { Text(it) }
            image?.let { Image(it, contentDescription = title,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit) }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.next_gallery_close)) } },
        dismissButton = { TextButton(onClick = { refresh++ }, enabled = !loading) {
            Text(stringResource(R.string.next_gallery_refresh))
        } })
}

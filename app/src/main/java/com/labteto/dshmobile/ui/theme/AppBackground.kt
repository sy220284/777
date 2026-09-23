package com.labteto.dshmobile.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Directory under `filesDir` that holds the one background image this app keeps. */
const val APP_BACKGROUND_DIR = "app-background"

/**
 * File name of the copied background image.
 *
 * A single slot rather than a history: picking another image replaces this one, and nothing would
 * ever read a second file, so keeping them would only grow the app's private storage forever.
 */
const val APP_BACKGROUND_FILE = "background"

/**
 * The image painted behind the whole app shell, or null for the plain theme colour.
 *
 * Screens that own a full-size root surface read this to decide whether to keep their own opaque
 * fill. Without it the image would be painted and then covered — the same picture, invisible.
 */
val LocalAppBackground = staticCompositionLocalOf<ImageBitmap?> { null }

/**
 * Paints [path] behind [content].
 *
 * Decoding runs off the main thread, and every failure — a path that no longer exists, a file that
 * is not an image, an out-of-memory decode — collapses to "no background". A background is
 * decoration; it must never be the reason a window comes up blank, or the reason the app crashes on
 * launch.
 */
@Composable
fun AppBackgroundHost(
    path: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val image = rememberBackgroundImage(path)
    CompositionLocalProvider(LocalAppBackground provides image) {
        Box(modifier.fillMaxSize()) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            content()
        }
    }
}

/**
 * Decode [path] once per path, on the IO dispatcher.
 *
 * Keyed on the path so replacing the image re-decodes, and left as `null` while the first decode is
 * in flight: an empty background for one frame beats blocking the frame on file IO.
 */
@Composable
private fun rememberBackgroundImage(path: String?): ImageBitmap? {
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        image = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return image
}

/**
 * The fill for a surface that covers the whole window.
 *
 * Transparent while a background image is in use, so the image painted by [AppBackgroundHost] shows
 * through; the plain [DsColors.bgBase] otherwise. Only window-covering surfaces switch to this —
 * cards, sheets and list rows keep their own opaque fills, which is what keeps text readable over a
 * photograph.
 */
@Composable
fun DsColors.rootSurface(): Color =
    if (LocalAppBackground.current != null) Color.Transparent else bgBase

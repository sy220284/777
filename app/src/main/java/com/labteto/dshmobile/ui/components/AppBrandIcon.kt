package com.labteto.dshmobile.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R

/** The launcher artwork, decoded at sidebar size so opening a drawer does not load a full-size image. */
@Composable
fun AppBrandIcon(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val bitmap = remember(resources) {
        BitmapFactory.decodeResource(
            resources,
            R.drawable.ic_launcher_portrait,
            BitmapFactory.Options().apply { inSampleSize = 8 },
        ).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
    )
}

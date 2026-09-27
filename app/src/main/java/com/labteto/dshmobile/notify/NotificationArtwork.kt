package com.labteto.dshmobile.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.labteto.dshmobile.R

/** Full-colour artwork shown in the notification body; the status bar uses a monochrome companion. */
internal object NotificationArtwork {
    @Volatile private var cached: Bitmap? = null

    fun largeIcon(context: Context): Bitmap? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: BitmapFactory.decodeResource(
                context.resources,
                R.drawable.notification_portrait,
            )?.also { cached = it }
        }
    }
}

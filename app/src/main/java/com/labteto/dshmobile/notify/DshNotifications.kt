package com.labteto.dshmobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local session notifications only. */
@Singleton
class DshNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Remove channels created by the retired remote-control client (also clears their notifications).
        listOf("completions", "needs_action", "connection").forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOCAL_JOBS, context.getString(R.string.notif_channel_local_jobs), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun canPost(): Boolean = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun postLocalSession(
        id: Int,
        title: String,
        text: String,
        sessionId: String?,
        notificationKey: String = sessionId?.takeIf(String::isNotBlank)?.let { "session:$it" } ?: "id:$id",
    ) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.Builder()
                .scheme("dshmobile")
                .authority("host")
                .appendPath("local-notification")
                .appendPath(notificationKey)
                .build()
            sessionId?.takeIf(String::isNotBlank)?.let {
                putExtra(EXTRA_LOCAL_SESSION_ID, it)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            "local:$notificationKey",
            id,
            NotificationCompat.Builder(context, CHANNEL_LOCAL_JOBS)
                .setSmallIcon(R.drawable.ic_notification_butterfly)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    companion object {
        const val CHANNEL_LOCAL_JOBS = "local-jobs"
        const val EXTRA_LOCAL_SESSION_ID = "dsh_local_session_id"
    }
}

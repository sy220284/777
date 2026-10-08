package com.labteto.dshmobile.connection

import com.labteto.dshmobile.observability.AppLog

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded (user opt-in via settings). The ConnectionManager owns the
 * actual connection; this service only pins the process with an ongoing
 * notification while it is running.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: DshNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val promoted = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { AppLog.error("ConnectionService", "后台连接前台服务提升失败", it) }
            .isSuccess
        if (!promoted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (connectionManager.state.value.host == null) {
            scope.launch { connectionManager.restoreDesiredConnectionIfNeeded() }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ ends the dataSync foreground budget; stop within seconds.
        AppLog.warn("ConnectionService", "后台连接前台服务达到系统时限，保留 WorkManager 定期恢复")
        try {
            runCatching { KeepAliveWorker.schedule(applicationContext) }
                .onFailure { AppLog.error("ConnectionService", "定期恢复任务调度失败", it) }
        } finally {
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // The service dying does not tear the connection down; the manager owns it.
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val host = connectionManager.state.value.host
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = host?.let { getString(R.string.notif_connected_text, it.authority) }
            ?: getString(R.string.common_connected)
        return NotificationCompat.Builder(this, DshNotifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_butterfly)
            .setContentTitle(getString(R.string.notif_connected_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.labteto.dshmobile.connection.STOP"
        private const val NOTIFICATION_ID = 10
    }
}

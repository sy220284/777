package com.labteto.dshmobile.local

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pins user-initiated on-device work while a work turn or background job is still alive.
 *
 * The service owns no execution itself; LocalHarnessEngine remains the single execution owner.
 * Keeping the process in foreground is enough to stop Android from freezing a long local turn when
 * the screen locks or the app moves behind another activity.
 */
@AndroidEntryPoint
class LocalExecutionService : Service() {
    @Inject lateinit var notifications: DshNotifications
    @Inject lateinit var hostsStore: HostsStore

    private data class Hold(
        val key: String,
        val sessionId: String,
        val label: String,
        val step: Int,
    )

    private val turns = linkedMapOf<String, Hold>()
    private val jobs = linkedMapOf<String, Hold>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService call must be promoted immediately even when this intent ends up
        // removing the final hold. It is stopped again below when the active set becomes empty.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(intent)) }

        when (intent?.action) {
            ACTION_TURN -> {
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                if (key.isNotBlank() && sessionId.isNotBlank()) {
                    turns[key] = Hold(
                        key = key,
                        sessionId = sessionId,
                        label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
                        step = intent.getIntExtra(EXTRA_STEP, 0),
                    )
                }
            }
            ACTION_RELEASE_TURN -> {
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                val released = turns.remove(key)
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                    ?.takeIf(String::isNotBlank)
                    ?: released?.sessionId
                val outcome = intent.getStringExtra(EXTRA_OUTCOME).orEmpty()
                if (sessionId != null) postCompletion(sessionId, outcome)
            }
            ACTION_SYNC_JOBS -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val ids = intent.getStringArrayListExtra(EXTRA_JOB_IDS).orEmpty()
                val labels = intent.getStringArrayListExtra(EXTRA_JOB_LABELS).orEmpty()
                jobs.clear()
                ids.forEachIndexed { index, id ->
                    if (id.isNotBlank() && sessionId.isNotBlank()) {
                        jobs[id] = Hold(
                            key = id,
                            sessionId = sessionId,
                            label = labels.getOrElse(index) { getString(R.string.local_execution_background_job) },
                            step = 0,
                        )
                    }
                }
            }
        }

        if (turns.isEmpty() && jobs.isEmpty()) {
            dispatchedActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(null))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(intent: Intent?): Notification {
        val previewStep = intent?.getIntExtra(EXTRA_STEP, 0) ?: 0
        val activeTurn = turns.values.lastOrNull()
        val step = activeTurn?.step?.takeIf { it > 0 } ?: previewStep.takeIf { it > 0 }
        val sessionId = activeTurn?.sessionId
            ?: turns.values.lastOrNull()?.sessionId
            ?: jobs.values.lastOrNull()?.sessionId
            ?: intent?.getStringExtra(EXTRA_SESSION_ID)
        val title = if (step != null) {
            getString(R.string.local_execution_notification_step, step)
        } else {
            getString(R.string.local_execution_notification_running)
        }
        val text = when {
            jobs.isNotEmpty() -> getString(R.string.local_execution_notification_jobs, jobs.size)
            activeTurn?.label?.isNotBlank() == true -> activeTurn.label
            else -> getString(R.string.local_execution_notification_body)
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sessionId?.takeIf(String::isNotBlank)?.let {
                putExtra(DshNotifications.EXTRA_LOCAL_SESSION_ID, it)
            }
        }
        val pending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DshNotifications.CHANNEL_LOCAL_JOBS)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun postCompletion(sessionId: String, outcome: String) {
        scope.launch {
            if (!runCatching { hostsStore.settingsOnce().notifyLocalJobs }.getOrDefault(true)) {
                return@launch
            }
            val failed = outcome == OUTCOME_FAILED
            val cancelled = outcome == OUTCOME_CANCELLED
            val title = getString(
                when {
                    failed -> R.string.local_execution_notification_failed
                    cancelled -> R.string.local_execution_notification_cancelled
                    else -> R.string.local_execution_notification_complete
                },
            )
            val text = if (jobs.isNotEmpty()) {
                getString(R.string.local_execution_notification_background_continues, jobs.size)
            } else {
                getString(R.string.local_execution_notification_tap)
            }
            notifications.postLocalSession(
                id = COMPLETION_ID_BASE + (sessionId.hashCode() and Int.MAX_VALUE) % 10_000,
                title = title,
                text = text,
                sessionId = sessionId,
            )
        }
    }

    companion object {
        private const val ACTION_TURN = "com.labteto.dshmobile.local.TURN"
        private const val ACTION_RELEASE_TURN = "com.labteto.dshmobile.local.RELEASE_TURN"
        private const val ACTION_SYNC_JOBS = "com.labteto.dshmobile.local.SYNC_JOBS"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_STEP = "step"
        private const val EXTRA_OUTCOME = "outcome"
        private const val EXTRA_JOB_IDS = "job_ids"
        private const val EXTRA_JOB_LABELS = "job_labels"
        private const val NOTIFICATION_ID = 7720
        private const val COMPLETION_ID_BASE = 23_000

        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"

        @Volatile private var dispatchedActive = false

        fun holdTurn(context: Context, sessionId: String, step: Int = 0) {
            dispatchedActive = true
            dispatch(
                context,
                Intent(context, LocalExecutionService::class.java)
                    .setAction(ACTION_TURN)
                    .putExtra(EXTRA_KEY, "turn:$sessionId")
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_LABEL, context.getString(R.string.local_execution_notification_body))
                    .putExtra(EXTRA_STEP, step),
            )
        }

        fun releaseTurn(context: Context, sessionId: String, outcome: String) {
            if (!dispatchedActive) return
            dispatch(
                context,
                Intent(context, LocalExecutionService::class.java)
                    .setAction(ACTION_RELEASE_TURN)
                    .putExtra(EXTRA_KEY, "turn:$sessionId")
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_OUTCOME, outcome),
            )
        }

        fun syncJobs(context: Context, sessionId: String, activeJobs: List<LocalJobInfo>) {
            if (activeJobs.isEmpty() && !dispatchedActive) return
            if (activeJobs.isNotEmpty()) dispatchedActive = true
            dispatch(
                context,
                Intent(context, LocalExecutionService::class.java)
                    .setAction(ACTION_SYNC_JOBS)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putStringArrayListExtra(EXTRA_JOB_IDS, ArrayList(activeJobs.map(LocalJobInfo::id)))
                    .putStringArrayListExtra(EXTRA_JOB_LABELS, ArrayList(activeJobs.map(LocalJobInfo::label))),
            )
        }

        private fun dispatch(context: Context, intent: Intent) {
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }
    }
}

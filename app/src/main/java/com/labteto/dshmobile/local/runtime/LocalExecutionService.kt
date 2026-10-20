package com.labteto.dshmobile.local.runtime

import android.app.Notification
import android.os.Handler
import android.os.Looper
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
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.tools.string
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.notify.LocalNotificationKind
import com.labteto.dshmobile.notify.stableNotificationId
import com.labteto.dshmobile.observability.AppLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pins user-initiated on-device work while a work turn or background job is still alive.
 *
 * The service owns no execution itself; Feature/Shared runtime owners remain authoritative while the process service only pins lifecycle.
 * The foreground notification pins process priority; a task-scoped partial wake lock keeps CPU
 * execution available with the screen off. Neither bypasses Doze network restrictions.
 */
@AndroidEntryPoint
class LocalExecutionService : Service() {
    @Inject lateinit var notifications: DshNotifications
    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var runtimeStateStore: LocalRuntimeStateStore

    private data class Hold(
        val key: String,
        val sessionId: String,
        val label: String,
        val step: Int,
    )

    private val cpuWakeLock by lazy {
        LocalExecutionWakeLock(getSystemService(POWER_SERVICE) as android.os.PowerManager)
    }
    private val turns = LocalExecutionTurnLedger()
    private var foregroundReady = false
    private val jobs = linkedMapOf<String, Hold>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        runningService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only an actual successful promotion authorizes a pending execution lease.
        val promoted = try {
            startForeground(NOTIFICATION_ID, buildNotification(intent))
            true
        } catch (error: IllegalStateException) {
            AppLog.error("LocalExecutionService", "前台服务提升被系统拒绝", error)
            runtimeStateStore.projection.publishError("前台执行服务启动受限，任务尚未开始")
            false
        } catch (error: SecurityException) {
            AppLog.error("LocalExecutionService", "前台服务缺少系统许可", error)
            runtimeStateStore.projection.publishError("前台执行服务缺少系统许可，任务尚未开始")
            false
        }
        if (!promoted) {
            foregroundReady = false
            cpuWakeLock.setActive(false)
            pendingTurns.clear()
            startupGate?.complete(false)
            startupGate = null
            stopSelf(startId)
            return START_NOT_STICKY
        }

        foregroundReady = true
        // Any valid promoted service fulfills the outstanding admissions, even if a
        // concurrent background-job synchronization delivered the first start intent.
        pendingTurns.values.forEach(turns::acquire)
        pendingTurns.clear()
        when (intent?.action) {
            ACTION_START_TURN -> Unit
            ACTION_SYNC_JOBS -> replaceJobs(
                intent.getStringArrayListExtra(EXTRA_JOB_IDS).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_JOB_LABELS).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_JOB_SESSION_IDS).orEmpty(),
            )
        }
        val active = !turns.isEmpty() || jobs.isNotEmpty()
        if (active) refreshNotification()
        startupGate?.complete(active)
        startupGate = null
        if (!active) {
            cpuWakeLock.setActive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun replaceJobs(ids: List<String>, labels: List<String>, sessionIds: List<String>) {
        jobs.clear()
        ids.forEachIndexed { index, id ->
            val sessionId = sessionIds.getOrNull(index).orEmpty()
            if (id.isNotBlank() && sessionId.isNotBlank()) {
                jobs[id] = Hold(id, sessionId,
                    labels.getOrElse(index) { getString(R.string.local_execution_background_job) }, 0)
            }
        }
        refreshNotification()
        stopIfIdle()
    }

    private fun admitTurn(hold: LocalExecutionTurnLedger.Hold) {
        check(foregroundReady) { "前台服务尚未完成提升" }
        turns.acquire(hold)
        refreshNotification()
    }

    private fun updateTurn(key: String, step: Int): Boolean {
        if (!foregroundReady || !turns.update(key, step)) return false
        refreshNotification()
        return true
    }

    private fun releaseTurn(key: String, outcome: String?, notify: Boolean) {
        val released = turns.release(key)
        if (released != null && notify && outcome != null) {
            postCompletion(released.sessionId, outcome)
        }
        refreshNotification()
        stopIfIdle()
    }

    private fun refreshNotification() {
        if (!foregroundReady || (turns.isEmpty() && jobs.isEmpty())) return
        cpuWakeLock.setActive(true)
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(null))
        } catch (error: SecurityException) {
            AppLog.error("LocalExecutionService", "前台通知权限被拒绝", error)
            runtimeStateStore.projection.publishError("前台执行通知权限异常")
        }
    }

    private fun stopIfIdle() {
        if (!turns.isEmpty() || jobs.isNotEmpty()) return
        foregroundReady = false
        cpuWakeLock.setActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cpuWakeLock.setActive(false)
        foregroundReady = false
        if (runningService === this) runningService = null
        startupGate?.complete(false)
        startupGate = null
        super.onDestroy()
    }

    private fun buildNotification(intent: Intent?): Notification {
        val previewStep = intent?.getIntExtra(EXTRA_STEP, 0) ?: 0
        val activeTurn = turns.latest()
        val step = activeTurn?.step?.takeIf { it > 0 } ?: previewStep.takeIf { it > 0 }
        val sessionId = activeTurn?.sessionId
            ?: turns.latest()?.sessionId
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
        notificationScope.launch {
            val notificationSettings = runCatching { hostsStore.settingsOnce() }
                    .onFailure { failure ->
                        // A cancelled lifecycle scope is expected when the service stops.
                        if (failure !is CancellationException) {
                            AppLog.failure("LocalExecutionService", "read_notification_setting", failure,
                                sessionId = sessionId)
                        }
                    }
                    .getOrNull() ?: return@launch
            if (!notificationSettings.notifyLocalJobs) return@launch
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
                id = stableNotificationId("local-execution", sessionId),
                title = title,
                text = text,
                sessionId = sessionId,
                notificationKey = "completion:$sessionId",
                settings = notificationSettings,
                kind = if (failed) LocalNotificationKind.FAILURE else LocalNotificationKind.RESULT,
            )
        }
    }

    companion object {
        private const val ACTION_START_TURN = "com.labteto.dshmobile.local.START_TURN"
        private const val ACTION_SYNC_JOBS = "com.labteto.dshmobile.local.SYNC_JOBS"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_STEP = "step"
        private const val EXTRA_JOB_IDS = "job_ids"
        private const val EXTRA_JOB_LABELS = "job_labels"
        private const val EXTRA_JOB_SESSION_IDS = "job_session_ids"
        private const val NOTIFICATION_ID = 7720

        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"

        // Android service lifecycle and admissions are all mutated on Dispatchers.Main.
        @Volatile private var runningService: LocalExecutionService? = null
        private var startupGate: CompletableDeferred<Boolean>? = null
        private val pendingTurns = linkedMapOf<String, LocalExecutionTurnLedger.Hold>()
        // Completion notification outlives the service's own foreground lifecycle.
        private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * An execution permit is returned only after Service.startForeground succeeds.
         * Updates and release operate on the already-running service, never issue another start.
         */
        class TurnLease internal constructor(private val key: String) {
            private var released = false

            suspend fun updateStep(step: Int): Boolean = withContext(Dispatchers.Main.immediate) {
                if (released) false else runningService?.updateTurn(key, step) == true
            }

            suspend fun release(outcome: String) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (!released) {
                        released = true
                        runningService?.releaseTurn(key, outcome, notify = true)
                    }
                }
            }
        }

        suspend fun acquireTurn(context: Context, sessionId: String): TurnLease? {
            val key = "turn:$sessionId:${UUID.randomUUID()}"
            val hold = LocalExecutionTurnLedger.Hold(
                key, sessionId, context.getString(R.string.local_execution_notification_body), 0)
            val startup = withContext(Dispatchers.Main.immediate) {
                val service = runningService
                if (service?.foregroundReady == true) {
                    service.admitTurn(hold)
                    CompletableDeferred(true)
                } else {
                    pendingTurns[key] = hold
                    startupGate ?: CompletableDeferred<Boolean>().also { gate ->
                        startupGate = gate
                        val intent = Intent(context, LocalExecutionService::class.java)
                            .setAction(ACTION_START_TURN)
                            .putExtra(EXTRA_SESSION_ID, sessionId)
                        if (!dispatch(context, intent)) {
                            startupGate = null
                            gate.complete(false)
                        }
                    }
                }
            }
            var granted = false
            try {
                val ready = withTimeoutOrNull(15_000L) { startup.await() } == true
                if (!ready) return null
                return withContext(Dispatchers.Main.immediate) {
                    if (runningService?.foregroundReady == true &&
                        runningService?.turns?.contains(key) == true
                    ) {
                        granted = true
                        TurnLease(key)
                    } else null
                }
            } finally {
                if (!granted) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        pendingTurns.remove(key)
                        runningService?.releaseTurn(key, null, notify = false)
                        // The admission deadline belongs to the waiting run. Once no
                        // request remains, an unfinished startup is no longer owned.
                        if (pendingTurns.isEmpty() && startupGate === startup &&
                            runningService?.foregroundReady != true
                        ) startupGate = null
                    }
                }
            }
        }

        /** All chat, group, reply-regeneration and Work turns use the same admitted lease. */
        suspend fun withTurn(
            context: Context,
            sessionId: String,
            error: () -> String?,
            onRejected: () -> Unit = {},
            block: suspend () -> Unit,
        ): Boolean {
            val lease = acquireTurn(context, sessionId)
            if (lease == null) {
                onRejected()
                return false
            }
            var outcome = OUTCOME_FAILED
            try {
                block()
                outcome = if (error() == null) OUTCOME_COMPLETED else OUTCOME_FAILED
                return true
            } catch (cancelled: CancellationException) {
                outcome = OUTCOME_CANCELLED
                throw cancelled
            } finally {
                lease.release(outcome)
            }
        }

        fun syncJobs(context: Context, activeJobs: List<LocalJobInfo>): Boolean {
            val owned = activeJobs.filter { !it.ownerSessionId.isNullOrBlank() }
            if (runningService?.foregroundReady == true) {
                Handler(Looper.getMainLooper()).post {
                    runningService?.replaceJobs(
                        owned.map(LocalJobInfo::id),
                        owned.map(LocalJobInfo::label),
                        owned.map { it.ownerSessionId.orEmpty() },
                    )
                }
                return true
            }
            if (owned.isEmpty()) return true
            return dispatch(context, Intent(context, LocalExecutionService::class.java)
                .setAction(ACTION_SYNC_JOBS)
                .putStringArrayListExtra(EXTRA_JOB_IDS, ArrayList(owned.map(LocalJobInfo::id)))
                .putStringArrayListExtra(EXTRA_JOB_LABELS, ArrayList(owned.map(LocalJobInfo::label)))
                .putStringArrayListExtra(
                    EXTRA_JOB_SESSION_IDS, ArrayList(owned.map { it.ownerSessionId.orEmpty() })),
            )
        }

        private fun dispatch(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context.applicationContext, intent)
            true
        } catch (error: IllegalStateException) {
            AppLog.error("LocalExecutionService", "系统拒绝启动前台服务", error)
            false
        } catch (error: SecurityException) {
            AppLog.error("LocalExecutionService", "前台服务权限不足", error)
            false
        }
    }
}

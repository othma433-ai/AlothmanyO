package com.alothmany.wa.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alothmany.wa.WaApplication
import com.alothmany.wa.accessibility.AutomationRuntime
import com.alothmany.wa.accessibility.WhatsAppUiBridge
import com.alothmany.wa.automation.ActionAutomationEngine
import com.alothmany.wa.automation.AutomationEngine
import com.alothmany.wa.recovery.RecoverableWork
import com.alothmany.wa.recovery.RecoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class AutomationForegroundService : Service() {
    companion object {
        const val ACTION_SYNC = "com.alothmany.wa.SYNC"
        const val ACTION_START_DEEP = "com.alothmany.wa.START_DEEP"
        const val ACTION_START_NEW = "com.alothmany.wa.START_NEW"
        const val ACTION_RUN_ACTION_JOB = "com.alothmany.wa.RUN_ACTION_JOB"
        const val ACTION_PAUSE = "com.alothmany.wa.PAUSE"
        const val ACTION_RESUME = "com.alothmany.wa.RESUME"
        const val ACTION_STOP = "com.alothmany.wa.STOP"
        const val ACTION_RECOVER = "com.alothmany.wa.RECOVER"
        const val ACTION_CAPTURE_DIAGNOSTIC = "com.alothmany.wa.CAPTURE_DIAGNOSTIC"
        const val EXTRA_PACKAGE = "target_package"
        const val EXTRA_ACTION_JOB_ID = "action_job_id"
        private const val CHANNEL = "wa_automation"
        private const val NOTIFICATION_ID = 2001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var extractionEngine: AutomationEngine? = null
    private var actionEngine: ActionAutomationEngine? = null
    private var activeExtractionJobId: String? = null
    private var activeActionJobId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val db get() = (application as WaApplication).database

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Ready"))
        if (intent == null) {
            recoverLatest()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_PAUSE -> pauseAutomation()
            ACTION_RESUME -> resumeAutomation()
            ACTION_STOP -> stopAutomation()
            ACTION_SYNC -> launchExtractionCommand(intent.getStringExtra(EXTRA_PACKAGE) ?: "com.whatsapp", syncOnly = true)
            ACTION_START_DEEP -> launchExtractionCommand(intent.getStringExtra(EXTRA_PACKAGE) ?: "com.whatsapp", mode = AutomationEngine.Mode.DEEP)
            ACTION_START_NEW -> launchExtractionCommand(intent.getStringExtra(EXTRA_PACKAGE) ?: "com.whatsapp", mode = AutomationEngine.Mode.NEW_ONLY)
            ACTION_RUN_ACTION_JOB -> intent.getStringExtra(EXTRA_ACTION_JOB_ID)?.let(::launchPersistedActionJob)
            ACTION_RECOVER -> recoverLatest()
            ACTION_CAPTURE_DIAGNOSTIC -> captureDiagnostic()
        }
        return START_STICKY
    }

    private fun captureDiagnostic() {
        scope.launch {
            val accessibility = awaitAccessibility() ?: run {
                updateNotification("Enable Accessibility service")
                return@launch
            }
            val path = WhatsAppUiBridge(accessibility).captureR7DiagnosticArtifact(
                runId = "manual-${System.currentTimeMillis()}",
                reason = "user-triggered"
            )
            updateNotification(if (path != null) "Diagnostic replay captured" else "Diagnostic capture unavailable")
        }
    }

    private fun pauseAutomation() {
        extractionEngine?.controller?.pause()
        actionEngine?.controller?.pause()
        // R7-M6: PAUSED is persisted by the engine only after it reaches an atomic boundary.
        updateNotification("Pause requested…")
    }

    private fun resumeAutomation() {
        if (worker?.isActive == true) {
            extractionEngine?.controller?.resume()
            actionEngine?.controller?.resume()
            activeExtractionJobId?.let { id -> scope.launch { updateExtractionStatus(id, "RUNNING") } }
            activeActionJobId?.let { id -> scope.launch { updateActionStatus(id, "RUNNING") } }
            updateNotification("Running")
        } else {
            updateNotification("Recovering paused job…")
            recoverLatest()
        }
    }

    private fun launchExtractionCommand(
        targetPackage: String,
        mode: AutomationEngine.Mode? = null,
        syncOnly: Boolean = false,
        recoverJobId: String? = null
    ) {
        if (worker?.isActive == true) return
        acquireWakeLock()
        worker = scope.launch {
            try {
                val accessibility = awaitAccessibility() ?: run {
                    updateNotification("Enable Accessibility service")
                    return@launch
                }
                extractionEngine = AutomationEngine(db, WhatsAppUiBridge(accessibility))
                actionEngine = null
                if (syncOnly) {
                    updateNotification("Synchronizing groups…")
                    val count = extractionEngine!!.syncGroups(targetPackage)
                    updateNotification("Groups synchronized: $count")
                } else if (mode != null) {
                    val id = recoverJobId ?: UUID.randomUUID().toString()
                    activeExtractionJobId = id
                    activeActionJobId = null
                    updateNotification(if (mode == AutomationEngine.Mode.DEEP) "Deep extraction running" else "New-only extraction running")
                    extractionEngine!!.start(mode, targetPackage, id)
                    updateNotification("Job: ${db.jobDao().get(id)?.status ?: "Finished"}")
                    activeExtractionJobId = null
                }
            } finally {
                releaseWakeLock()
            }
        }
    }

    private fun launchPersistedActionJob(jobId: String) {
        if (worker?.isActive == true) return
        acquireWakeLock()
        worker = scope.launch {
            try {
                val job = db.actionJobDao().get(jobId) ?: run {
                    updateNotification("Action job not found")
                    return@launch
                }
                val accessibility = awaitAccessibility() ?: run {
                    updateNotification("Enable Accessibility service")
                    return@launch
                }
                actionEngine = ActionAutomationEngine(db, WhatsAppUiBridge(accessibility))
                extractionEngine = null
                activeActionJobId = job.id
                activeExtractionJobId = null
                updateNotification(actionNotification(job.type, recovering = false))
                val status = runActionJobByType(actionEngine!!, job.type, job.id)
                updateNotification("Action job: $status")
                activeActionJobId = null
            } finally {
                releaseWakeLock()
            }
        }
    }

    private fun recoverLatest() {
        if (worker?.isActive == true) return
        acquireWakeLock()
        worker = scope.launch {
            try {
                val recoverable = RecoveryManager(db).latestWork()
                if (recoverable == null) {
                    updateNotification("No recoverable job")
                    return@launch
                }
                val accessibility = awaitAccessibility() ?: run {
                    updateNotification("Enable Accessibility service")
                    return@launch
                }
                when (recoverable) {
                    is RecoverableWork.Extraction -> {
                        val job = recoverable.job
                        val mode = runCatching { AutomationEngine.Mode.valueOf(job.mode) }.getOrDefault(AutomationEngine.Mode.DEEP)
                        extractionEngine = AutomationEngine(db, WhatsAppUiBridge(accessibility))
                        actionEngine = null
                        activeExtractionJobId = job.id
                        updateNotification("Recovering ${job.mode} job")
                        extractionEngine!!.start(mode, job.targetPackage, job.id)
                        updateNotification("Job: ${db.jobDao().get(job.id)?.status ?: "Finished"}")
                        activeExtractionJobId = null
                    }
                    is RecoverableWork.Action -> {
                        val job = recoverable.job
                        actionEngine = ActionAutomationEngine(db, WhatsAppUiBridge(accessibility))
                        extractionEngine = null
                        activeActionJobId = job.id
                        updateNotification(actionNotification(job.type, recovering = true))
                        val status = runActionJobByType(actionEngine!!, job.type, job.id)
                        updateNotification("Action job: $status")
                        activeActionJobId = null
                    }
                }
            } finally {
                releaseWakeLock()
            }
        }
    }


    private suspend fun runActionJobByType(engine: ActionAutomationEngine, type: String, jobId: String): String = when (type) {
        "SCAN_INVITES" -> engine.runInviteScan(jobId)
        "JOIN" -> engine.runJoin(jobId)
        "PUBLISH" -> engine.runPublish(jobId)
        else -> {
            updateActionStatus(jobId, "FAILED")
            "FAILED"
        }
    }

    private fun actionNotification(type: String, recovering: Boolean): String {
        val prefix = if (recovering) "Recovering " else ""
        return prefix + when (type) {
            "SCAN_INVITES" -> "invite scan queue"
            "JOIN" -> "invite join queue"
            "PUBLISH" -> "publish queue"
            else -> "unknown action queue"
        }
    }

    private suspend fun awaitAccessibility() = withTimeoutOrNull(10_000) {
        while (AutomationRuntime.accessibility == null) delay(200)
        AutomationRuntime.accessibility
    }

    private fun stopAutomation() {
        extractionEngine?.controller?.stop()
        actionEngine?.controller?.stop()
        activeExtractionJobId?.let { id -> scope.launch { updateExtractionStatus(id, "STOPPED") } }
        activeActionJobId?.let { id -> scope.launch { updateActionStatus(id, "STOPPED") } }
        worker?.cancel()
        worker = null
        releaseWakeLock()
        updateNotification("Stopped")
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private suspend fun updateExtractionStatus(id: String, status: String) {
        val job = db.jobDao().get(id) ?: return
        val now = System.currentTimeMillis()
        db.jobDao().upsert(job.copy(status = status, updatedAt = now, lastProgressAt = now))
    }

    private suspend fun updateActionStatus(id: String, status: String) {
        val job = db.actionJobDao().get(id) ?: return
        val now = System.currentTimeMillis()
        db.actionJobDao().upsert(job.copy(status = status, updatedAt = now, lastProgressAt = now))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "WA Automation", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("WA Al-Othmany")
        .setContentText(text)
        .setOngoing(true)
        .addAction(android.R.drawable.ic_media_pause, "Pause", servicePending(ACTION_PAUSE, 1))
        .addAction(android.R.drawable.ic_media_play, "Resume", servicePending(ACTION_RESUME, 2))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", servicePending(ACTION_STOP, 3))
        .build()

    private fun servicePending(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, AutomationForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun updateNotification(text: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, notification(text))
        } catch (_: SecurityException) {
            // Permission can be revoked while the service is running.
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WAAlOthmany:Automation").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        worker?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

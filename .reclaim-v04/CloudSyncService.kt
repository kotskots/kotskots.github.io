package com.kostas.reclaim

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CloudSyncService : Service() {
    companion object {
        private const val CHANNEL_ID = "reclaim_cloud_sync"
        private const val NOTIFICATION_ID = 7402
        private const val ACTION_STOP = "com.kostas.reclaim.CLOUD_SYNC_STOP"

        fun start(context: Context) {
            if (!CloudSyncPreferences.isConfigured(context)) return
            val intent = Intent(context, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CloudSyncService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Multi-device sync active"))
        if (loopJob?.isActive != true) loopJob = scope.launch { syncLoop() }
        return START_STICKY
    }

    private suspend fun syncLoop() {
        var heartbeatTick = 0
        while (scope.isActive && CloudSyncPreferences.isConfigured(this)) {
            try {
                val remote = CloudSyncClient.fetchState(this).getOrThrow()
                val localUpdated = FocusPreferences.updatedAt(this)
                when {
                    remote.updatedAt > localUpdated -> {
                        if (CloudSyncClient.applyIfNewer(this, remote)) syncVpnState()
                    }
                    localUpdated > remote.updatedAt -> CloudSyncClient.pushLocalState(this)
                }
                heartbeatTick++
                if (heartbeatTick >= 5) {
                    CloudSyncClient.heartbeat(this)
                    heartbeatTick = 0
                }
            } catch (_: Exception) {
                // Offline is intentional: local rules stay active and the next successful poll reconciles state.
            }
            delay(3_000L)
        }
        stopSelf()
    }

    private fun syncVpnState() {
        if (!WebsitePreferences.enabled(this)) return
        if (VpnService.prepare(this) == null) DnsVpnService.start(this)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Reclaim Sync")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cloud sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

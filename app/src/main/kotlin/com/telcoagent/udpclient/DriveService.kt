package com.telcoagent.udpclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DriveSessionState(
    val active: Boolean = false,
    val statusText: String = "",
    val roundCount: Int = 0,
    val logText: String = "",
)

class DriveService : Service() {
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private val driveClient = DriveClient()
    private var driveJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val logLines = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning()) startDrive()
            }
            ACTION_STOP -> stopDrive()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        driveJob?.cancel()
        releaseWakeLock()
        supervisorJob.cancel()
        super.onDestroy()
    }

    private fun startDrive() {
        logLines.clear()
        publishState(
            DriveSessionState(
                active = true,
                statusText = "Starting drive MTU test…",
                roundCount = 0,
                logText = "",
            ),
        )

        acquireWakeLock()
        val notification = buildNotification("Drive MTU probe running…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        driveJob?.cancel()
        driveJob = serviceScope.launch {
            var round = 0
            try {
                while (true) {
                    round++
                    appendLog("═══ Round $round ═══")
                    publishState(_state.value.copy(
                        statusText = "Round $round running…",
                        roundCount = round,
                    ))
                    updateNotification("Drive MTU · Round $round")

                    val result = driveClient.runMtuRound(
                        host = "netprobe.xyz",
                        onLog = { line -> appendLog(line) },
                    )

                    // Save MTU from result
                    val wgMtu = result.wgMtu
                    if (wgMtu != null) {
                        AnalysisPreferences.setMtu(this@DriveService, wgMtu)
                        appendLog("Round $round: WG MTU = $wgMtu ✓")
                    } else {
                        appendLog("Round $round: MTU discovery failed")
                    }

                    appendLog("Round $round done ✓\n")
                    publishState(_state.value.copy(
                        statusText = "Round $round done · next in 5s…",
                        roundCount = round,
                    ))

                    delay(5000) // pause between rounds
                }
            } catch (e: CancellationException) {
                appendLog("Drive test stopped after $round rounds")
                publishState(_state.value.copy(statusText = "Stopped after $round rounds"))
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                publishState(_state.value.copy(statusText = "Error: ${e.message}"))
            } finally {
                releaseWakeLock()
                publishState(_state.value.copy(active = false))
                updateNotification(_state.value.statusText)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopDrive() {
        driveJob?.cancel()
        driveJob = null
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        // Keep last 500 lines to avoid OOM
        while (logLines.size > 500) logLines.removeAt(0)
        publishState(_state.value.copy(logText = logLines.joinToString("\n")))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetProbe:Drive").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1 hour max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Drive MTU test",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Continuous ICMP MTU discovery"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, DriveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NetProbe Drive Test")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun publishState(state: DriveSessionState) {
        _state.value = state
    }

    companion object {
        const val ACTION_START = "com.telcoagent.udpclient.action.START_DRIVE"
        const val ACTION_STOP = "com.telcoagent.udpclient.action.STOP_DRIVE"

        private const val NOTIFICATION_ID = 1004
        private const val CHANNEL_ID = "drive_abr"

        private val _state = MutableStateFlow(DriveSessionState())
        val state: StateFlow<DriveSessionState> = _state.asStateFlow()

        fun isRunning(): Boolean = _state.value.active

        fun start(context: Context) {
            val intent = Intent(context, DriveService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DriveService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

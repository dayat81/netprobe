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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MaxSessionState(
    val active: Boolean = false,
    val statusText: String = "",
    val progressPct: Int = 0,
    val showProgress: Boolean = false,
    val logText: String = "",
    val result: MaxResult? = null,
)

class MaxService : Service() {
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private val maxClient = MaxClient()
    private var maxJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val logLines = mutableListOf<String>()
    @Volatile
    private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning()) {
                    startMax()
                }
            }
            ACTION_STOP -> stopMax()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelled = true
        maxJob?.cancel()
        releaseWakeLock()
        supervisorJob.cancel()
        super.onDestroy()
    }

    private fun startMax() {
        logLines.clear()
        cancelled = false
        publishState(
            MaxSessionState(
                active = true,
                statusText = getString(R.string.max_status_running),
                showProgress = true,
                logText = "",
                result = null,
                progressPct = 0,
            ),
        )

        acquireWakeLock()
        val notification = buildNotification(getString(R.string.max_notification_starting))
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

        maxJob?.cancel()
        maxJob = serviceScope.launch {
            try {
                val result = maxClient.runMaxTest(
                    config = MaxConfig(),
                    onLog = { line -> appendLog(line) },
                    onProgress = { current, total ->
                        val pct = (current * 100) / total
                        publishState(
                            _state.value.copy(
                                progressPct = pct,
                                statusText = "Testing ${current + 1}/$total stream levels",
                            ),
                        )
                        updateNotification("Testing stream level ${current + 1}/$total")
                    },
                    isCancelled = { cancelled },
                )
                publishResult(result)
                appendLog("MAX test complete")
            } catch (e: CancellationException) {
                appendLog("MAX test stopped")
                publishState(_state.value.copy(statusText = getString(R.string.max_stopped)))
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                publishState(_state.value.copy(statusText = "Failed: ${e.message}"))
            } finally {
                releaseWakeLock()
                publishState(
                    _state.value.copy(
                        active = false,
                        showProgress = false,
                    ),
                )
                updateNotification(_state.value.statusText)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopMax() {
        cancelled = true
        maxJob?.cancel()
        maxJob = null
    }

    private fun publishResult(result: MaxResult) {
        publishState(
            _state.value.copy(
                result = result,
                progressPct = 100,
                statusText = "Done",
            ),
        )
        updateNotification("MAX test done")
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        publishState(_state.value.copy(logText = logLines.joinToString("\n")))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetProbe:MAX").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
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
            getString(R.string.max_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.max_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, MaxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.max_notification_title))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.max_stop_action), stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun publishState(state: MaxSessionState) {
        _state.value = state
    }

    companion object {
        const val ACTION_START = "com.telcoagent.udpclient.action.START_MAX"
        const val ACTION_STOP = "com.telcoagent.udpclient.action.STOP_MAX"

        private const val NOTIFICATION_ID = 1004
        private const val CHANNEL_ID = "max_probe"

        private val _state = MutableStateFlow(MaxSessionState())
        val state: StateFlow<MaxSessionState> = _state.asStateFlow()

        fun isRunning(): Boolean = _state.value.active

        fun start(context: Context) {
            val intent = Intent(context, MaxService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MaxService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

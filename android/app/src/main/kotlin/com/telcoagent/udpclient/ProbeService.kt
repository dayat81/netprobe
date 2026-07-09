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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ProbeSessionState(
    val active: Boolean = false,
    val statusText: String = "",
    val progressPct: Int = 0,
    val showProgress: Boolean = false,
    val logText: String = "",
    val roundMetrics: RoundMetrics? = null,
    val overallMetrics: OverallMetrics? = null,
    val roundsLabel: String? = null,
)

class ProbeService : Service() {
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private lateinit var collector: CellInfoCollector
    private lateinit var logRecordStore: LogRecordStore
    private val udpProbeClient = UdpProbeClient()
    private var probeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val logLines = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        collector = CellInfoCollector(this)
        logRecordStore = LogRecordStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning()) {
                    startProbe()
                }
            }
            ACTION_STOP -> stopProbe()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        probeJob?.cancel()
        releaseWakeLock()
        supervisorJob.cancel()
        super.onDestroy()
    }

    private fun probeConfig(): ProbeConfig {
        return ProbeConfig(
            applyOffsetCorrection = ProbePreferences.isOffsetCorrectionEnabled(this),
        )
    }

    private fun startProbe() {
        logLines.clear()
        publishState(
            ProbeSessionState(
                active = true,
                statusText = getString(R.string.status_running),
                showProgress = true,
                logText = "",
                roundMetrics = null,
                overallMetrics = null,
                roundsLabel = null,
                progressPct = 0,
            ),
        )

        acquireWakeLock()
        val notification = buildNotification(getString(R.string.probe_notification_starting))
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

        probeJob?.cancel()
        probeJob = serviceScope.launch {
            if (ProviderResolver.needsIspLookup(this@ProbeService)) {
                IspLookup.refresh()
            }
            val config = probeConfig()
            val reportWriter = ProbeReportWriter(this@ProbeService)
            val cellJob = launch { collectRadioSamples(reportWriter) }
            try {
                val overall = udpProbeClient.runProbe(
                    config = config,
                    onLog = { line -> appendLog(line) },
                    onProgress = { progress -> publishProgress(progress) },
                    onRoundDone = { metrics -> publishRound(metrics, config.rounds) },
                )
                publishOverall(overall)
                appendLog("All rounds complete")
                cellJob.cancel()
                uploadProbeResult(reportWriter, overall, config)
            } catch (e: CancellationException) {
                appendLog("Probe stopped")
                publishState(_state.value.copy(statusText = getString(R.string.probe_stopped)))
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                publishState(_state.value.copy(statusText = "Failed: ${e.message}"))
                cellJob.cancel()
                saveFailedProbeReport(reportWriter, config, e.message)
            } finally {
                cellJob.cancel()
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

    private fun stopProbe() {
        probeJob?.cancel()
        probeJob = null
    }

    private suspend fun collectRadioSamples(reportWriter: ProbeReportWriter) {
        if (!collector.hasPermission()) return
        val executor = mainExecutor
        while (coroutineContext.isActive) {
            try {
                withContext(Dispatchers.Main) {
                    collector.requestFreshCells(executor)
                }
                reportWriter.recordCell(collector.collect())
            } catch (_: Exception) {
                // keep probing even if one sample fails
            }
            delay(CELL_INTERVAL_MS)
        }
    }

    private suspend fun uploadProbeResult(
        reportWriter: ProbeReportWriter,
        overall: OverallMetrics,
        config: ProbeConfig,
    ) {
        appendLog(getString(R.string.probe_uploading))
        updateNotification(getString(R.string.probe_uploading))
        val result = withContext(Dispatchers.IO) {
            val lineCount = maxOf(reportWriter.sampleCount(), 1)
            val file = reportWriter.write(overall, config)
            val record = logRecordStore.addRecord(file.name, file.absolutePath, lineCount)
            LogSyncHelper.sync(this@ProbeService, record.id)
        }
        result.fold(
            onSuccess = { sessionId ->
                appendLog(getString(R.string.probe_uploaded, sessionId))
                appendLog(getString(R.string.probe_saved_to_logs))
                publishState(
                    _state.value.copy(
                        statusText = getString(R.string.probe_done_uploaded, sessionId),
                    ),
                )
            },
            onFailure = { error ->
                appendLog(getString(R.string.probe_upload_failed, error.message ?: "error"))
                appendLog(getString(R.string.probe_saved_to_logs))
            },
        )
    }

    private suspend fun saveFailedProbeReport(
        reportWriter: ProbeReportWriter,
        config: ProbeConfig,
        error: String?,
    ) {
        withContext(Dispatchers.IO) {
            val lineCount = maxOf(reportWriter.sampleCount(), 1)
            val file = reportWriter.writeFailed(config, error)
            logRecordStore.addRecord(
                filename = file.name,
                filePath = file.absolutePath,
                lineCount = lineCount,
                syncStatus = SyncStatus.FAILED,
                syncError = error,
            )
        }
        appendLog(getString(R.string.probe_saved_to_logs))
    }

    private fun publishProgress(progress: ProbeProgress) {
        val totalSteps = progress.totalRounds * progress.totalPackets
        val completed = (progress.roundNo - 1) * progress.totalPackets + progress.packetNo
        val pct = (completed * 100) / totalSteps
        val status = "Round ${progress.roundNo}/${progress.totalRounds} · " +
            "Packet ${progress.packetNo}/${progress.totalPackets}"
        publishState(
            _state.value.copy(
                progressPct = pct,
                statusText = status,
                roundsLabel = "${progress.roundNo - 1}/${progress.totalRounds}",
            ),
        )
        updateNotification(status)
    }

    private fun publishRound(metrics: RoundMetrics, totalRounds: Int) {
        publishState(
            _state.value.copy(
                roundMetrics = metrics,
                roundsLabel = "${metrics.roundNo}/$totalRounds",
            ),
        )
    }

    private fun publishOverall(overall: OverallMetrics) {
        publishState(
            _state.value.copy(
                overallMetrics = overall,
                roundsLabel = "${overall.perfectRounds}/${overall.rounds.size} ✓",
                statusText = "Done — ${overall.perfectRounds}/${overall.rounds.size} rounds zero loss",
            ),
        )
        updateNotification(_state.value.statusText)
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        publishState(_state.value.copy(logText = logLines.joinToString("\n")))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetProbe:Probe").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
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
            getString(R.string.probe_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.probe_channel_desc)
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
            2,
            Intent(this, ProbeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.probe_notification_title))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.probe_stop), stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun publishState(state: ProbeSessionState) {
        _state.value = state
    }

    companion object {
        const val ACTION_START = "com.telcoagent.udpclient.action.START_PROBE"
        const val ACTION_STOP = "com.telcoagent.udpclient.action.STOP_PROBE"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "manual_probe"
        private const val CELL_INTERVAL_MS = 2_000L

        private val _state = MutableStateFlow(ProbeSessionState())
        val state: StateFlow<ProbeSessionState> = _state.asStateFlow()

        fun isRunning(): Boolean = _state.value.active

        fun start(context: Context) {
            val intent = Intent(context, ProbeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProbeService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

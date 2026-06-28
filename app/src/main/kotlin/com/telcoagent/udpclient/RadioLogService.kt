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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress

data class RadioLogSessionState(
    val active: Boolean = false,
    val fileName: String? = null,
    val lineCount: Int = 0,
    val lastUdpLatencyMs: Double? = null,
    val probing: Boolean = false,
    val lastSnapshot: CellInfoSnapshot? = null,
)

class RadioLogService : Service() {
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private lateinit var collector: CellInfoCollector
    private lateinit var logRecordStore: LogRecordStore
    private var logWriter: RadioLogWriter? = null
    private var currentLogFile: File? = null
    private var cellJob: Job? = null
    private var networkJob: Job? = null
    private var probeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cachedNetworkExtras: LogSessionExtras? = null
    private var frozenLogStartUdp: LogSessionExtras? = null
    private var sessionClockOffsetMs: Double? = null
    private val udpProbeClient = UdpProbeClient()

    override fun onCreate() {
        super.onCreate()
        collector = CellInfoCollector(this)
        logRecordStore = LogRecordStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isLogging()) {
                    startLogging()
                }
            }
            ACTION_STOP -> stopLogging()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        cellJob?.cancel()
        networkJob?.cancel()
        probeJob?.cancel()
        logWriter?.stop()
        logWriter = null
        supervisorJob.cancel()
        super.onDestroy()
    }

    private fun startLogging() {
        if (!collector.hasPermission()) {
            stopSelf()
            return
        }

        val writer = RadioLogWriter(this)
        val file = writer.start()
        logWriter = writer
        currentLogFile = file
        cachedNetworkExtras = null
        frozenLogStartUdp = null
        sessionClockOffsetMs = null
        publishState(
            RadioLogSessionState(
                active = true,
                fileName = file.name,
                lineCount = 0,
            ),
        )

        acquireWakeLock()
        val notification = buildNotification(file.name, 0, probing = true)
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

        serviceScope.launch {
            if (ProviderResolver.needsIspLookup(this@RadioLogService)) {
                IspLookup.refresh()
            }
            startNetworkCollect()
            startProbeLoop()
            startCellLoop()
        }
    }

    private fun stopLogging() {
        cellJob?.cancel()
        networkJob?.cancel()
        probeJob?.cancel()
        cellJob = null
        networkJob = null
        probeJob = null
        cachedNetworkExtras = null
        frozenLogStartUdp = null
        sessionClockOffsetMs = null

        val writer = logWriter
        val lines = writer?.lineCount ?: 0
        val file = writer?.stop()
        logWriter = null
        currentLogFile = null
        releaseWakeLock()
        publishState(RadioLogSessionState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (file != null) {
            val record = logRecordStore.addRecord(file.name, file.absolutePath, lines)
            stoppedRecord.tryEmit(record)
        }
    }

    private fun startCellLoop() {
        cellJob?.cancel()
        cellJob = serviceScope.launch {
            val executor = mainExecutor
            while (isActive && logWriter?.isActive == true) {
                try {
                    withContext(Dispatchers.Main) {
                        collector.requestFreshCells(executor)
                    }
                    val snapshot = collector.collect()
                    logWriter?.append(snapshot)
                    val lines = logWriter?.lineCount ?: 0
                    publishState(
                        _state.value.copy(
                            lineCount = lines,
                            lastSnapshot = snapshot,
                        ),
                    )
                    updateNotification()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // keep logging even if one sample fails
                }
                delay(CELL_INTERVAL_MS)
            }
        }
    }

    private fun startNetworkCollect() {
        networkJob?.cancel()
        networkJob = serviceScope.launch {
            try {
                val network = withContext(Dispatchers.IO) {
                    NetworkInfoCollector.read(this@RadioLogService)
                }
                val extras = network.toSessionExtras()
                cachedNetworkExtras = extras
                publishSessionExtras()
            } catch (_: Exception) {
                // network extras are optional
            }
        }
    }

    private fun publishSessionExtras() {
        val network = cachedNetworkExtras ?: LogSessionExtras()
        val frozen = frozenLogStartUdp
        logWriter?.setSessionExtras(
            network.copy(
                udpLatencyMs = frozen?.udpLatencyMs,
                udpLossPct = frozen?.udpLossPct,
                udpJitterMs = frozen?.udpJitterMs,
                udpUplinkMs = frozen?.udpUplinkMs,
                udpDownlinkMs = frozen?.udpDownlinkMs,
                udpUplinkLossPct = frozen?.udpUplinkLossPct,
                udpDownlinkLossPct = frozen?.udpDownlinkLossPct,
            ),
        )
    }

    private fun freezeLogStartUdp(udp: OverallMetrics) {
        frozenLogStartUdp = LogSessionExtras(
            udpLatencyMs = udp.avgRttMs ?: udp.avgLatencyMs,
            udpLossPct = udp.lossPct,
            udpJitterMs = udp.jitterMs,
            udpUplinkMs = udp.avgUplinkMs,
            udpDownlinkMs = udp.avgDownlinkMs,
            udpUplinkLossPct = udp.uplinkLossPct,
            udpDownlinkLossPct = udp.downlinkLossPct,
        )
        publishSessionExtras()
    }

    private fun startProbeLoop() {
        probeJob?.cancel()
        probeJob = serviceScope.launch {
            sessionClockOffsetMs = measureSessionClockOffset()

            while (isActive && logWriter?.isActive == true) {
                publishState(_state.value.copy(probing = true, lastUdpLatencyMs = null))
                updateNotification()
                try {
                    val isLogStart = frozenLogStartUdp == null
                    val config = if (isLogStart) {
                        UdpProbeClient.logStartConfig(this@RadioLogService)
                    } else {
                        UdpProbeClient.continuousConfig(this@RadioLogService)
                    }.copy(fixedOffsetMs = sessionClockOffsetMs)
                    val udp = udpProbeClient.runProbe(
                        config = config,
                        onLog = {},
                        onProgress = {},
                        onRoundDone = {},
                    )
                    val latency = udp.avgRttMs ?: udp.avgLatencyMs
                    if (isLogStart) {
                        if (cachedNetworkExtras == null) {
                            cachedNetworkExtras = withContext(Dispatchers.IO) {
                                NetworkInfoCollector.read(this@RadioLogService).toSessionExtras()
                            }
                        }
                        freezeLogStartUdp(udp)
                    }
                    publishState(
                        _state.value.copy(
                            probing = false,
                            lastUdpLatencyMs = latency,
                        ),
                    )
                    updateNotification()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    publishState(_state.value.copy(probing = false))
                    updateNotification()
                }
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private suspend fun measureSessionClockOffset(): Double? {
        val config = UdpProbeClient.continuousConfig(this)
        if (!config.applyOffsetCorrection || config.syncPackets <= 0) return null
        return withContext(Dispatchers.IO) {
            try {
                val target = InetAddress.getByName(config.host)
                val socket = DatagramSocket()
                try {
                    socket.soTimeout = config.recvTimeoutMs
                    udpProbeClient.measureClockOffset(socket, target, config)
                } finally {
                    socket.close()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun NetworkInfoSnapshot.toSessionExtras(): LogSessionExtras {
        return LogSessionExtras(
            localIp = localIp,
            dnsServers = dnsServers,
            networkType = networkType,
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetProbe:RadioLog").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.radio_log_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.radio_log_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(fileName: String, lineCount: Int, probing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RadioLogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val status = buildString {
            append(getString(R.string.radio_logging, fileName, lineCount))
            if (probing) {
                append(" · ")
                append(getString(R.string.radio_udp_probing))
            } else {
                _state.value.lastUdpLatencyMs?.let { rtt ->
                    append(" · ")
                    append(getString(R.string.radio_udp_rtt, rtt))
                }
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.radio_log_notification_title))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.radio_stop_log), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val fileName = currentLogFile?.name ?: return
        val notification = buildNotification(
            fileName,
            logWriter?.lineCount ?: 0,
            _state.value.probing,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun publishState(state: RadioLogSessionState) {
        _state.value = state
    }

    companion object {
        const val ACTION_START = "com.telcoagent.udpclient.action.START_RADIO_LOG"
        const val ACTION_STOP = "com.telcoagent.udpclient.action.STOP_RADIO_LOG"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "radio_log"
        private const val CELL_INTERVAL_MS = 2_000L
        private const val PROBE_INTERVAL_MS = 5_000L

        private val _state = MutableStateFlow(RadioLogSessionState())
        val state: StateFlow<RadioLogSessionState> = _state.asStateFlow()

        private val stoppedRecord = MutableSharedFlow<LogRecord>(extraBufferCapacity = 1)
        val stoppedRecords: SharedFlow<LogRecord> = stoppedRecord.asSharedFlow()

        fun isLogging(): Boolean = _state.value.active

        fun start(context: Context) {
            val intent = Intent(context, RadioLogService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RadioLogService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

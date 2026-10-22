package com.aksa.mysignal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Sole owner of radio sampling: one [RadioSampler] tick every 3 s on a worker thread,
 * kept alive with the screen off by a foreground notification and a partial wake lock.
 */
class RadioService : Service() {
    private lateinit var sampler: RadioSampler
    private lateinit var nrReporter: NrStatusReporter
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextTickAt = 0L
    private var lastSurfaceKey: String? = null
    private var lastSurfaceMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                val snapshot = sampler.sample()
                RadioState.publish(snapshot)
                logTick(snapshot)
                if (snapshot.error == null) MeasureUploader.enqueue(this@RadioService, snapshot.toMeasureJson())
                val nr = snapshot.toNrSnapshot()
                nrReporter.maybePost(nr)
                refreshSurfaces(snapshot, nr)
            } catch (t: Throwable) {
                RadioState.fail("${t.javaClass.simpleName}: ${t.message}")
                Log.w(TAG, "tick failed", t)
            }
            holdWakeLock()
            val now = SystemClock.uptimeMillis()
            nextTickAt += INTERVAL_MS
            if (nextTickAt <= now) nextTickAt = now + INTERVAL_MS
            handler?.postAtTime(this, nextTickAt)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startInForeground(buildNotification(null))
        sampler = RadioSampler(applicationContext)
        nrReporter = NrStatusReporter(applicationContext)
        holdWakeLock()
        val thread = HandlerThread("mysignal-sampler").apply { start() }
        worker = thread
        handler = Handler(thread.looper).also {
            nextTickAt = SystemClock.uptimeMillis() + 500L
            it.postAtTime(tick, nextTickAt)
        }
        Log.i(TAG, "sampling every ${INTERVAL_MS}ms")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RadioState.setSamplingEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        MeasureUploader.kick(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacks(tick)
        worker?.quitSafely()
        handler = null
        worker = null
        sampler.close()
        nrReporter.close()
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        Log.i(TAG, "sampling stopped after ${RadioState.samples} samples")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logTick(snapshot: RadioSnapshot) {
        val primary = snapshot.primary
        val bw = primary?.cellBandwidthKhz.orEmpty()
        val subs = snapshot.subscriptions.joinToString(" ") { radio ->
            "${radio.plmn}:ch${radio.channel ?: "-"}:nr${radio.nrState}:def${if (radio.isDefaultData) 1 else 0}"
        }
        val point = RadioState.chartPoints().lastOrNull()
        val plot = point?.cells
            ?.joinToString(" ") { "${it.plmn}:${it.rat}:${it.earfcn}${if (it.held) "h" else ""}" }
            .orEmpty()
        val signal = point?.cells
            ?.filter { it.rsrp != null || it.sinr != null }
            ?.joinToString(" ") { "${it.rat}:${it.earfcn}:r${it.rsrp ?: "-"}:s${it.sinr ?: "-"}" }
            .orEmpty()
        Log.i(
            TAG,
            "tick plmn=${primary?.plmn} ch=${primary?.channel ?: "-"} nr=${primary?.nrState} ca=${primary?.ca} bw=${if (bw.isEmpty()) "-" else RadioRules.mhzLabel(bw)} subs=$subs plot=$plot signal=$signal",
        )
    }

    /** Widget and notification follow the verdict immediately and otherwise refresh every 15 s. */
    private fun refreshSurfaces(snapshot: RadioSnapshot, nr: NrSnapshot) {
        val key = "${nr.verdict.summary}|${nr.nrState}|${snapshot.primary?.ca}|${snapshot.error}"
        val now = System.currentTimeMillis()
        if (key == lastSurfaceKey && now - lastSurfaceMs < SURFACE_REFRESH_MS) return
        lastSurfaceKey = key
        lastSurfaceMs = now
        NrWidgetUpdater.updateAll(applicationContext, nr)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Location type keeps cell info readable with the screen off; it needs a foreground start.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "location FGS refused, using specialUse: ${e.message}")
            }
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun holdWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mysignal:sampler")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        runCatching { lock?.acquire(WAKE_MS) }
    }

    private fun buildNotification(snapshot: RadioSnapshot?): Notification {
        val sub = snapshot?.primary
        val upload = MeasureUploader.status(this)
        val title = when {
            snapshot?.error != null -> "netprobe · ${snapshot.error}"
            sub != null -> "netprobe · ${sub.plmn.ifEmpty { sub.carrier }} ${sub.verdict.summary}"
            else -> "netprobe · starting"
        }
        val text = buildString {
            if (sub != null) {
                append("nr=${NrLabels.nrState(sub.nrState)} ca=${sub.ca ?: "?"} ")
                append("ch=${sub.channel ?: "?"} rsrp=${sub.servingSignal?.rsrp ?: "?"} · ")
            }
            append("q=${upload.queued} up=${upload.uploaded}")
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_sampling), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.monitor_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MySignalSampler"
        const val INTERVAL_MS = 3_000L
        private const val WAKE_MS = 60_000L
        private const val SURFACE_REFRESH_MS = 15_000L
        private const val CHANNEL_ID = "mysignal_sampler"
        private const val NOTIFICATION_ID = 7105
        const val ACTION_STOP = "com.aksa.mysignal.action.STOP_SAMPLING"

        fun start(context: Context) {
            if (!RadioState.samplingEnabled(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java))
            }.onFailure { Log.w(TAG, "start refused: ${it.message}") }
        }

        fun enable(context: Context) {
            RadioState.setSamplingEnabled(context, true)
            start(context)
        }

        fun stop(context: Context) {
            RadioState.setSamplingEnabled(context, false)
            context.stopService(Intent(context, RadioService::class.java))
        }
    }
}

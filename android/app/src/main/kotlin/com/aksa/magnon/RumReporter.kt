package com.aksa.magnon

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * App health reports: one session per process, a 5 minute heartbeat, captured failures,
 * and the previous process exit. Crash files are written before the process dies and
 * uploaded on the next start. Nothing here carries IMEI, IMSI, or location.
 */
object RumReporter {
    private const val HEARTBEAT_MS = 5 * 60_000L
    private const val SESSION_FALLBACK_MS = 8_000L
    private const val MAX_FILES = 20
    private const val MAX_BATCH = 8
    private const val MAX_BATCH_CHARS = 200_000
    private const val DEBOUNCE_MS = 60_000L
    private const val PREFS = "mysignal_rum"
    private const val KEY_EXIT = "exit_ts"

    private val startedActivities = AtomicInteger(0)
    private val sessionSent = AtomicBoolean(false)
    private val debounce = HashMap<String, Long>()
    private val signal = Object()
    private val spoolLock = Any()

    @Volatile private var app: Context? = null
    @Volatile private var processStartElapsed = 0L
    @Volatile private var coldStartMs: Long? = null
    @Volatile private var speedSummary: String = ""
    @Volatile private var thread: Thread? = null

    fun start(application: Application) {
        app = application.applicationContext
        processStartElapsed = SystemClock.elapsedRealtime()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { crashed, error ->
            runCatching { writeCrash(application, crashed, error) }
            previous?.uncaughtException(crashed, error)
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        reportPreviousExits(application)
        Handler(Looper.getMainLooper()).postDelayed({ sendSession() }, SESSION_FALLBACK_MS)
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val context = app ?: continue
                runCatching { enqueue(context, heartbeat(context)) }
                kick(context)
            }
        }, "mysignal-rum-beat").apply {
            isDaemon = true
            start()
        }
        kick(application)
    }

    fun onFirstFrame() {
        if (processStartElapsed > 0L) {
            coldStartMs = (SystemClock.elapsedRealtime() - processStartElapsed).coerceAtLeast(0L)
        }
        sendSession()
    }

    fun noteSpeed(result: SpeedtestEngine.Result) {
        speedSummary = String.format(
            Locale.US,
            "dl=%.1f ul=%.1f ping=%d",
            result.downloadMbps,
            result.uploadMbps,
            result.pingMs,
        )
    }

    fun nonfatal(code: String, message: String, error: Throwable? = null) {
        val context = app ?: return
        val now = System.currentTimeMillis()
        synchronized(debounce) {
            val previous = debounce[code] ?: 0L
            if (now - previous < DEBOUNCE_MS) return
            debounce[code] = now
        }
        runCatching {
            enqueue(context, base(context, "nonfatal", "error", code, message, stackOf(error), AppLog.recent(40)))
        }
        kick(context)
    }

    private fun sendSession() {
        val context = app ?: return
        if (!sessionSent.compareAndSet(false, true)) return
        runCatching { enqueue(context, session(context)) }
        kick(context)
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val message = thread.name + ": " + error.javaClass.name + ": " + (error.message ?: "")
        enqueue(
            context,
            base(context, "crash", "error", error.javaClass.simpleName, message, stackOf(error), AppLog.recent(40)),
        )
    }

    private fun session(context: Context): JSONObject {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val shown = coldStartMs != null || startedActivities.get() > 0
        return base(context, "session", "info", "session", "start", null, null, shown).apply {
            coldStartMs?.let { put("cold_start_ms", it.coerceIn(0L, 600_000L)) }
            if (abi.isNotBlank()) put("abi", RumEvent.clip(abi, 32))
        }
    }

    private fun heartbeat(context: Context): JSONObject {
        val status = MeasureUploader.status(context)
        val network = network(context)
        val rat = rat()
        val message = buildString {
            append(network)
            if (!rat.isNullOrBlank()) append(' ').append(rat)
            append(if (startedActivities.get() > 0) " fg" else " bg")
            append(" q=").append(status.queued)
            append(" http=").append(status.lastCode)
            if (speedSummary.isNotBlank()) append(' ').append(speedSummary)
        }
        return base(context, "heartbeat", "info", "heartbeat", message, null, null).apply {
            put("queue", status.queued.coerceIn(0, 100_000))
            put("upload_code", status.lastCode.coerceIn(-1, 999))
            if (speedSummary.isNotBlank()) put("speed", RumEvent.clip(speedSummary, 120))
        }
    }

    private fun reportPreviousExits(context: Context) {
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_EXIT, 0L)
            var newest = seen
            for (info in manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)) {
                if (info.timestamp <= seen) continue
                val reason = exitReason(info.reason) ?: continue
                enqueue(
                    context,
                    base(context, "exit", "error", reason, "previous process $reason", null, null),
                )
                if (info.timestamp > newest) newest = info.timestamp
            }
            if (newest > seen) prefs.edit().putLong(KEY_EXIT, newest).apply()
        }
    }

    private fun exitReason(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        else -> null
    }

    private fun base(
        context: Context,
        kind: String,
        level: String,
        code: String,
        message: String,
        stack: String?,
        logs: List<AppLog.Line>?,
        foreground: Boolean = startedActivities.get() > 0,
    ): JSONObject = RumEvent.build(
        id = "rum" + CollectorClient.randomHex(8),
        tsMs = System.currentTimeMillis(),
        kind = kind,
        level = level,
        code = code,
        message = message,
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        sdk = Build.VERSION.SDK_INT,
        model = Build.MODEL ?: "-",
        manufacturer = Build.MANUFACTURER ?: "-",
        network = network(context),
        rat = rat(),
        foreground = foreground,
        stack = stack,
        logs = logs,
    )

    private fun stackOf(error: Throwable?): String? {
        error ?: return null
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun network(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun rat(): String? = null

    private fun enqueue(context: Context, event: JSONObject) {
        synchronized(spoolLock) {
            val folder = folder(context)
            val name = event.optString("event_id").ifBlank { "rum" }
            val file = File(folder, "${System.currentTimeMillis()}-$name.json")
            FileOutputStream(file).use { stream ->
                stream.write(event.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            val files = folder.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { it.delete() }
        }
    }

    private fun kick(context: Context) {
        if (!BuildConfig.REPORTING_ENABLED) return
        val current = thread
        if (current != null && current.isAlive) {
            synchronized(signal) { signal.notifyAll() }
            return
        }
        synchronized(this) {
            if (thread?.isAlive == true) return
            val application = context.applicationContext
            thread = Thread({ drain(application) }, "mysignal-rum").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun drain(context: Context) {
        var backoff = 0L
        var idle = 0L
        while (!Thread.currentThread().isInterrupted) {
            val pending = folder(context).listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            if (pending.isEmpty()) {
                if (idle >= 120_000L) break
                synchronized(signal) { signal.wait(1_000L) }
                idle += 1_000L
                continue
            }
            idle = 0L
            val chosen = ArrayList<File>()
            val events = JSONArray()
            synchronized(spoolLock) {
                var chars = 0
                for (file in pending) {
                    if (chosen.size >= MAX_BATCH) break
                    val text = runCatching { file.readText() }.getOrNull()
                    if (text == null) {
                        file.delete()
                        continue
                    }
                    if (chosen.isNotEmpty() && chars + text.length > MAX_BATCH_CHARS) break
                    val parsed = runCatching { JSONObject(text) }.getOrNull()
                    if (parsed == null) {
                        file.delete()
                        continue
                    }
                    events.put(parsed)
                    chosen += file
                    chars += text.length
                }
            }
            if (chosen.isEmpty()) continue
            val now = System.currentTimeMillis()
            val body = JSONObject()
                .put("schema_version", 1)
                .put("device_id", CollectorClient.deviceId(context))
                .put("batch_id", "rumb" + CollectorClient.randomHex(8))
                .put("sent_at_ms", now)
                .put("events", events)
            val code = runCatching {
                CollectorClient.post(CollectorClient.RUM_PATH, body.toString().toByteArray(Charsets.UTF_8), now)
            }.getOrDefault(-1)
            when {
                code == 200 -> synchronized(spoolLock) { chosen.forEach { it.delete() } }
                code in 400..499 && code != 408 && code != 429 -> synchronized(spoolLock) { chosen.forEach { it.delete() } }
                else -> {
                    backoff = if (backoff <= 0L) 2_000L else minOf(backoff * 2, 60_000L)
                    Thread.sleep(backoff)
                }
            }
            if (code == 200) backoff = 0L
        }
        thread = null
    }

    private fun folder(context: Context): File =
        File(context.applicationContext.filesDir, "rum-spool").also { it.mkdirs() }
}

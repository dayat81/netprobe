package com.aksa.mysignal

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

data class UploadStatus(
    val queued: Int,
    val uploaded: Long,
    val failed: Long,
    val dropped: Long,
    val lastCode: Int,
    val lastMessage: String,
    val lastOkMs: Long,
)

/**
 * Single ordered consumer of [MeasureSpool]. Sampling only enqueues; this thread does all
 * network I/O so a slow or offline collector never delays the 3 s tick.
 */
object MeasureUploader {
    private const val TAG = "MySignalUpload"
    private const val MAX_BATCH = 12
    private const val MAX_BATCH_BYTES = 140_000L
    private const val BACKOFF_MIN_MS = 2_000L
    private const val BACKOFF_MAX_MS = 60_000L
    private const val IDLE_EXIT_MS = 120_000L

    private val signal = Object()
    @Volatile private var thread: Thread? = null
    @Volatile private var spool: MeasureSpool? = null
    @Volatile private var uploaded = 0L
    @Volatile private var failed = 0L
    @Volatile private var dropped = 0L
    @Volatile private var lastCode = 0
    @Volatile private var lastMessage = "idle"
    @Volatile private var lastOkMs = 0L

    fun spool(context: Context): MeasureSpool {
        spool?.let { return it }
        synchronized(this) {
            spool?.let { return it }
            return MeasureSpool(File(context.applicationContext.filesDir, "measure-spool")).also { spool = it }
        }
    }

    fun enqueue(context: Context, snapshot: JSONObject) {
        if (!CollectorClient.measureEnabled) return
        spool(context).enqueue(snapshot.toString())
        kick(context)
    }

    fun status(context: Context): UploadStatus = UploadStatus(
        queued = spool(context).count(),
        uploaded = uploaded,
        failed = failed,
        dropped = dropped,
        lastCode = lastCode,
        lastMessage = if (CollectorClient.measureEnabled) lastMessage else "disabled (no measure key in build)",
        lastOkMs = lastOkMs,
    )

    fun kick(context: Context) {
        val current = thread
        if (current != null && current.isAlive) {
            synchronized(signal) { signal.notifyAll() }
            return
        }
        synchronized(this) {
            if (thread?.isAlive == true) return
            val app = context.applicationContext
            thread = Thread({ drain(app) }, "mysignal-upload").apply {
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun drain(context: Context) {
        val queue = spool(context)
        var backoff = 0L
        var idle = 0L
        var singles = 0
        while (!Thread.currentThread().isInterrupted) {
            val batch = queue.peek(if (singles > 0) 1 else MAX_BATCH, MAX_BATCH_BYTES)
            if (batch.isEmpty()) {
                if (idle >= IDLE_EXIT_MS) break
                synchronized(signal) { signal.wait(1_000L) }
                idle += 1_000L
                continue
            }
            idle = 0L
            val snapshots = JSONArray()
            val readable = ArrayList<File>()
            batch.forEach { file ->
                val parsed = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
                if (parsed == null) {
                    queue.remove(listOf(file))
                    dropped += 1
                } else {
                    snapshots.put(parsed)
                    readable += file
                }
            }
            if (readable.isEmpty()) continue
            val plain = JSONObject()
                .put("snapshots", snapshots)
                .put("tests", JSONArray())
                .put("os_results", JSONArray())
            val code = try {
                CollectorClient.postMeasurement(context, plain)
            } catch (e: Exception) {
                lastMessage = "error ${e.javaClass.simpleName}"
                -1
            }
            lastCode = code
            if (singles > 0) singles -= 1
            when {
                code == 200 -> {
                    queue.remove(readable)
                    uploaded += readable.size
                    lastOkMs = System.currentTimeMillis()
                    lastMessage = "ok ${readable.size}"
                    backoff = 0L
                }
                code in 400..499 && code != 408 && code != 429 -> {
                    if (readable.size > 1) {
                        // Find the rejected snapshot without discarding the valid ones around it.
                        singles = readable.size
                        lastMessage = "HTTP $code, retrying singly"
                    } else {
                        queue.remove(readable)
                        dropped += 1
                        lastMessage = "dropped HTTP $code"
                        Log.w(TAG, "snapshot rejected code=$code")
                    }
                }
                else -> {
                    failed += 1
                    if (code > 0) lastMessage = "retry HTTP $code"
                    backoff = if (backoff <= 0L) BACKOFF_MIN_MS else minOf(backoff * 2, BACKOFF_MAX_MS)
                    Log.w(TAG, "upload $lastMessage wait=$backoff")
                    Thread.sleep(backoff)
                }
            }
        }
        thread = null
    }
}

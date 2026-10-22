package com.aksa.mysignal

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Durable FIFO of MEASURE snapshots, one JSON file per 3 s sample.
 *
 * File names sort chronologically, so trimming always drops the oldest samples first.
 * Writes go through a temp file and rename so the uploader never reads a partial file.
 */
class MeasureSpool(
    private val dir: File,
    private val maxFiles: Int = 4_800,
    private val maxBytes: Long = 96L * 1024L * 1024L,
    private val ttlMs: Long = 6L * 60L * 60L * 1000L,
) {
    private val lock = Any()
    private var sequence = 0

    fun enqueue(json: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val name = String.format(Locale.US, "%013d-%04d.json", nowMs, sequence++ % 10_000)
        val temp = File(dir, "$name.tmp")
        val target = File(dir, name)
        try {
            temp.writeBytes(json.toByteArray(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        trim(nowMs)
        true
    }

    /** Oldest files that fit the batch limits; always at least one file when the queue is not empty. */
    fun peek(maxItems: Int, maxBytes: Long): List<File> = synchronized(lock) {
        val batch = ArrayList<File>()
        var total = 0L
        for (file in files()) {
            val size = file.length()
            if (batch.isNotEmpty() && (batch.size >= maxItems || total + size > maxBytes)) break
            batch += file
            total += size
        }
        batch
    }

    fun remove(files: Collection<File>) = synchronized(lock) {
        files.forEach { it.delete() }
    }

    fun count(): Int = synchronized(lock) { files().size }

    private fun files(): List<File> =
        dir.listFiles { file -> file.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    private fun trim(nowMs: Long) {
        val files = files()
        var total = files.sumOf { it.length() }
        val cutoff = nowMs - ttlMs
        var remaining = files.size
        for (file in files) {
            val tooMany = remaining > maxFiles
            val tooBig = total > maxBytes
            val expired = file.lastModified() < cutoff
            if (!tooMany && !tooBig && !expired) break
            total -= file.length()
            file.delete()
            remaining -= 1
        }
    }
}

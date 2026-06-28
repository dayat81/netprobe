package com.telcoagent.udpclient

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RadioLogWriter(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var logFile: File? = null
    private var sessionExtras: LogSessionExtras? = null
    var lineCount: Int = 0
        private set

    val isActive: Boolean
        get() = writer != null

    fun start(): File {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "radio-$stamp.csv")
        logFile = file
        lineCount = 0
        sessionExtras = null
        writer = file.bufferedWriter().apply {
            write(CsvLogFormat.HEADER)
            newLine()
        }
        return file
    }

    fun setSessionExtras(extras: LogSessionExtras) {
        sessionExtras = extras
    }

    fun append(snapshot: CellInfoSnapshot) {
        val w = writer ?: return
        w.write(CsvLogFormat.formatRow(snapshot, sessionExtras))
        w.newLine()
        w.flush()
        lineCount++
    }

    fun stop(): File? {
        writer?.close()
        writer = null
        sessionExtras = null
        return logFile
    }
}

package com.aksa.magnon

import android.util.Log
import java.util.ArrayDeque

/**
 * In-process copy of the lines we also send to logcat.
 * The app cannot read logcat back, so crash reports attach this ring instead.
 */
object AppLog {
    const val CAP = 150
    const val LINE = 180

    data class Line(val tsMs: Long, val level: String, val tag: String, val message: String)

    private val lock = Any()
    private val ring = ArrayDeque<Line>()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        note("info", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        note("info", tag, message)
    }

    fun i(tag: String, message: String, error: Throwable) {
        Log.i(tag, message, error)
        note("info", tag, "$message ${error.javaClass.simpleName}: ${error.message}")
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        note("warn", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
        note("warn", tag, "$message ${error.javaClass.simpleName}: ${error.message}")
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        note("error", tag, message)
    }

    fun e(tag: String, message: String, error: Throwable) {
        Log.e(tag, message, error)
        note("error", tag, "$message ${error.javaClass.simpleName}: ${error.message}")
    }

    fun note(level: String, tag: String, message: String) {
        val line = Line(
            tsMs = System.currentTimeMillis(),
            level = level,
            tag = RumEvent.clip(tag, 40),
            message = RumEvent.clip(message, LINE),
        )
        synchronized(lock) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast(line)
        }
    }

    fun recent(limit: Int): List<Line> = synchronized(lock) {
        val start = (ring.size - limit).coerceAtLeast(0)
        ring.drop(start)
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
    }
}

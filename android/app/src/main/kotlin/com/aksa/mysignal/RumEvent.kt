package com.aksa.mysignal

import org.json.JSONArray
import org.json.JSONObject

/** JSON for one `/v1/rum` event. Stack and log lines are only attached to crash and nonfatal. */
object RumEvent {
    const val MESSAGE = 512
    const val STACK = 8_000

    fun clip(value: String?, limit: Int): String {
        if (value.isNullOrBlank()) return "-"
        val cleaned = buildString(value.length.coerceAtMost(limit + 8)) {
            for (ch in value) {
                if (ch.code < 32 || ch.code == 127) append(' ') else append(ch)
            }
        }.trim()
        val text = cleaned.ifEmpty { "-" }
        return if (text.length <= limit) text else text.substring(0, limit)
    }

    fun clipStack(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = buildString(value.length.coerceAtMost(STACK + 8)) {
            for (ch in value) {
                when {
                    ch == '\n' -> append('\n')
                    ch == '\r' -> Unit
                    ch.code < 32 || ch.code == 127 -> append(' ')
                    else -> append(ch)
                }
            }
        }.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= STACK) cleaned else cleaned.substring(0, STACK)
    }

    fun build(
        id: String,
        tsMs: Long,
        kind: String,
        level: String,
        code: String,
        message: String,
        appVersion: String,
        versionCode: Int,
        sdk: Int,
        model: String,
        manufacturer: String,
        network: String? = null,
        rat: String? = null,
        foreground: Boolean? = null,
        coldStartMs: Long? = null,
        queue: Int? = null,
        uploadCode: Int? = null,
        speed: String? = null,
        abi: String? = null,
        stack: String? = null,
        logs: List<AppLog.Line>? = null,
    ): JSONObject {
        val event = JSONObject()
            .put("event_id", id)
            .put("ts_ms", tsMs)
            .put("kind", kind)
            .put("level", level)
            .put("code", clip(code, 64))
            .put("message", clip(message, MESSAGE))
            .put("app_version", clip(appVersion, 32))
            .put("version_code", versionCode)
            .put("sdk", sdk)
            .put("model", clip(model, 64))
            .put("manufacturer", clip(manufacturer, 64))
        text(event, "network", network, 32)
        text(event, "rat", rat, 16)
        if (foreground != null) event.put("foreground", foreground)
        if (coldStartMs != null) event.put("cold_start_ms", coldStartMs.coerceIn(0L, 600_000L))
        if (queue != null) event.put("queue", queue.coerceIn(0, 100_000))
        if (uploadCode != null) event.put("upload_code", uploadCode.coerceIn(-1, 999))
        text(event, "speed", speed, 120)
        text(event, "abi", abi, 32)
        if (kind == "crash" || kind == "nonfatal") {
            clipStack(stack)?.let { event.put("stack", it) }
            if (!logs.isNullOrEmpty()) {
                val arr = JSONArray()
                logs.take(40).forEach { line ->
                    arr.put(
                        JSONObject()
                            .put("ts_ms", line.tsMs)
                            .put("level", line.level)
                            .put("tag", clip(line.tag, 40))
                            .put("message", clip(line.message, AppLog.LINE)),
                    )
                }
                event.put("logs", arr)
            }
        }
        return event
    }

    private fun text(event: JSONObject, key: String, value: String?, limit: Int) {
        if (value.isNullOrBlank()) return
        event.put(key, clip(value, limit))
    }
}

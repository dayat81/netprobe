package com.telcoagent.udpclient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LogRecordStore(context: Context) {
    private val baseDir = context.getExternalFilesDir(null)
    private val logsDir = File(baseDir, "logs").apply { mkdirs() }
    private val probesDir = File(baseDir, "probes").apply { mkdirs() }
    private val indexFile = File(logsDir, "records.json")

    fun addRecord(
        filename: String,
        filePath: String,
        lineCount: Int,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        syncError: String? = null,
    ): LogRecord {
        allRecords().firstOrNull { it.filePath == filePath }?.let { return it }
        val record = LogRecord(
            id = UUID.randomUUID().toString(),
            filename = filename,
            filePath = filePath,
            lineCount = lineCount,
            createdAt = System.currentTimeMillis(),
            syncStatus = syncStatus,
            syncError = syncError,
        )
        save(allRecords().toMutableList().apply { add(0, record) })
        return record
    }

    fun refreshFromDisk(): List<LogRecord> {
        val records = allRecords().toMutableList()
        val knownPaths = records.map { it.filePath }.toSet()
        val discovered = mutableListOf<LogRecord>()
        for (dir in listOf(logsDir, probesDir)) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".csv") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    if (file.absolutePath in knownPaths) return@forEach
                    discovered.add(
                        LogRecord(
                            id = UUID.randomUUID().toString(),
                            filename = file.name,
                            filePath = file.absolutePath,
                            lineCount = countCsvRows(file),
                            createdAt = file.lastModified(),
                            syncStatus = SyncStatus.PENDING,
                        ),
                    )
                }
        }
        if (discovered.isNotEmpty()) {
            records.addAll(0, discovered)
            save(records)
        }
        return records
    }

    private fun countCsvRows(file: File): Int {
        return try {
            file.readLines().count { it.isNotBlank() } - 1
        } catch (_: Exception) {
            0
        }
    }

    fun allRecords(): List<LogRecord> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val array = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toRecord())
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getRecord(id: String): LogRecord? = allRecords().firstOrNull { it.id == id }

    fun updateSyncStatus(
        id: String,
        status: SyncStatus,
        sessionId: Int? = null,
        error: String? = null,
    ) {
        val updated = allRecords().map { record ->
            if (record.id != id) return@map record
            record.copy(
                syncStatus = status,
                sessionId = sessionId ?: record.sessionId,
                syncError = error,
                syncedAt = if (status == SyncStatus.SYNCED) System.currentTimeMillis() else record.syncedAt,
            )
        }
        save(updated)
    }

    private fun save(records: List<LogRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        indexFile.writeText(array.toString())
    }

    private fun LogRecord.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("filename", filename)
            put("filePath", filePath)
            put("lineCount", lineCount)
            put("createdAt", createdAt)
            put("syncStatus", syncStatus.name)
            put("sessionId", sessionId ?: JSONObject.NULL)
            put("syncError", syncError ?: JSONObject.NULL)
            put("syncedAt", syncedAt ?: JSONObject.NULL)
        }
    }

    private fun JSONObject.toRecord(): LogRecord {
        return LogRecord(
            id = getString("id"),
            filename = getString("filename"),
            filePath = getString("filePath"),
            lineCount = getInt("lineCount"),
            createdAt = getLong("createdAt"),
            syncStatus = SyncStatus.valueOf(getString("syncStatus")),
            sessionId = if (isNull("sessionId")) null else getInt("sessionId"),
            syncError = if (isNull("syncError")) null else getString("syncError"),
            syncedAt = if (isNull("syncedAt")) null else getLong("syncedAt"),
        )
    }
}

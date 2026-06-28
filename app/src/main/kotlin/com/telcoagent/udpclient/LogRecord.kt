package com.telcoagent.udpclient

enum class SyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED,
}

data class LogRecord(
    val id: String,
    val filename: String,
    val filePath: String,
    val lineCount: Int,
    val createdAt: Long,
    val syncStatus: SyncStatus,
    val sessionId: Int? = null,
    val syncError: String? = null,
    val syncedAt: Long? = null,
)

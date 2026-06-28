package com.telcoagent.udpclient

import android.content.Context
import java.io.File

object LogSyncHelper {
    suspend fun sync(context: Context, recordId: String): Result<Int> {
        val store = LogRecordStore(context)
        val record = store.getRecord(recordId)
            ?: return Result.failure(IllegalStateException("Record not found"))

        val file = File(record.filePath)
        if (!file.exists()) {
            store.updateSyncStatus(recordId, SyncStatus.FAILED, error = "File missing")
            return Result.failure(IllegalStateException("File missing"))
        }

        store.updateSyncStatus(recordId, SyncStatus.SYNCING)
        val result = RadioLogUploader().upload(file)
        result.fold(
            onSuccess = { sessionId ->
                store.updateSyncStatus(recordId, SyncStatus.SYNCED, sessionId = sessionId)
            },
            onFailure = { error ->
                store.updateSyncStatus(recordId, SyncStatus.FAILED, error = error.message)
            },
        )
        return result
    }
}

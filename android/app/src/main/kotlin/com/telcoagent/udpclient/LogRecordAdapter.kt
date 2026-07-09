package com.telcoagent.udpclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.telcoagent.udpclient.databinding.ItemLogRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogRecordAdapter(
    private val onRetry: (LogRecord) -> Unit,
) : RecyclerView.Adapter<LogRecordAdapter.ViewHolder>() {
    private val items = mutableListOf<LogRecord>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(records: List<LogRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemLogRecordBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: LogRecord) {
            val context = binding.root.context
            binding.logFilename.text = if (record.filename.startsWith("probe-")) {
                context.getString(R.string.logs_probe_label, record.filename)
            } else {
                record.filename
            }
            binding.logMeta.text = context.getString(
                R.string.logs_item_meta,
                record.lineCount,
                dateFormat.format(Date(record.createdAt)),
            )

            val (label, colorRes) = when (record.syncStatus) {
                SyncStatus.PENDING -> R.string.sync_pending to R.color.sync_pending
                SyncStatus.SYNCING -> R.string.sync_syncing to R.color.sync_syncing
                SyncStatus.SYNCED -> R.string.sync_synced to R.color.sync_synced
                SyncStatus.FAILED -> R.string.sync_failed to R.color.sync_failed
            }
            binding.logSyncStatus.text = context.getString(label)
            binding.logSyncStatus.setTextColor(ContextCompat.getColor(context, colorRes))

            when (record.syncStatus) {
                SyncStatus.SYNCED -> {
                    binding.logSyncDetail.visibility = View.VISIBLE
                    binding.logSyncDetail.text = context.getString(
                        R.string.logs_synced_detail,
                        record.sessionId ?: "—",
                    )
                    binding.logRetryButton.visibility = View.GONE
                }
                SyncStatus.FAILED -> {
                    binding.logSyncDetail.visibility = View.VISIBLE
                    binding.logSyncDetail.text = record.syncError ?: context.getString(R.string.sync_failed)
                    binding.logRetryButton.visibility = View.VISIBLE
                    binding.logRetryButton.setOnClickListener { onRetry(record) }
                }
                SyncStatus.PENDING -> {
                    binding.logSyncDetail.visibility = View.GONE
                    binding.logRetryButton.visibility = View.VISIBLE
                    binding.logRetryButton.text = context.getString(R.string.logs_sync_now)
                    binding.logRetryButton.setOnClickListener { onRetry(record) }
                }
                SyncStatus.SYNCING -> {
                    binding.logSyncDetail.visibility = View.GONE
                    binding.logRetryButton.visibility = View.GONE
                }
            }
        }
    }
}

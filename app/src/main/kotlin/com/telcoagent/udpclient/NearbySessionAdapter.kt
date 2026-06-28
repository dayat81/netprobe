package com.telcoagent.udpclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.telcoagent.udpclient.databinding.ItemNearbySessionBinding

class NearbySessionAdapter : RecyclerView.Adapter<NearbySessionAdapter.ViewHolder>() {
    private val items = mutableListOf<NearbyProbeSession>()

    fun submitList(sessions: List<NearbyProbeSession>) {
        items.clear()
        items.addAll(sessions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNearbySessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemNearbySessionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(session: NearbyProbeSession) {
            val context = binding.root.context
            val whenText = NearbyProviderFormatter.formatLastSeen(session.uploadedAt)
            binding.sessionTitle.text = context.getString(
                R.string.around_you_session_title,
                session.sessionId,
                whenText,
            )

            val distance = session.distanceKm?.let {
                context.getString(R.string.around_you_distance_km, it)
            } ?: context.getString(R.string.value_dash)
            val device = session.deviceId ?: context.getString(R.string.value_dash)
            val tech = NearbyProviderFormatter.formatConnection(
                tech = session.tech,
                techSummary = session.techSummary,
                networkType = session.networkType,
                connectionLabel = session.connectionLabel,
            )
            val config = session.config ?: context.getString(R.string.value_dash)
            binding.sessionMeta.text = context.getString(
                R.string.around_you_session_meta,
                distance,
                device,
                tech,
                config,
            )

            if (session.failed) {
                binding.sessionStatus.visibility = View.VISIBLE
                binding.sessionStatus.text = context.getString(
                    R.string.around_you_session_failed,
                    session.failureMessage ?: context.getString(R.string.value_dash),
                )
            } else {
                binding.sessionStatus.visibility = View.GONE
            }

            binding.sessionUplink.text = NearbyProviderFormatter.formatMs(session.udpUplinkMs)
            binding.sessionDownlink.text = NearbyProviderFormatter.formatMs(session.udpDownlinkMs)
            binding.sessionRtt.text = NearbyProviderFormatter.formatMs(session.udpLatencyMs)
            binding.sessionLoss.text = NearbyProviderFormatter.formatPct(session.udpLossPct)
            binding.sessionJitter.text = NearbyProviderFormatter.formatMs(session.udpJitterMs)

            binding.sessionUplink.setTextColor(
                ContextCompat.getColor(context, latencyColor(session.udpUplinkMs)),
            )
            binding.sessionDownlink.setTextColor(
                ContextCompat.getColor(context, latencyColor(session.udpDownlinkMs)),
            )
            binding.sessionRtt.setTextColor(
                ContextCompat.getColor(context, latencyColor(session.udpLatencyMs)),
            )
            binding.sessionLoss.setTextColor(
                ContextCompat.getColor(context, lossColor(session.udpLossPct)),
            )
            binding.sessionJitter.setTextColor(
                ContextCompat.getColor(context, jitterColor(session.udpJitterMs)),
            )
        }

        private fun latencyColor(value: Double?): Int {
            if (value == null) return R.color.metric_latency
            return when {
                value <= 80 -> R.color.metric_latency_ok
                value <= 150 -> R.color.metric_latency_warn
                else -> R.color.metric_latency_bad
            }
        }

        private fun lossColor(value: Double?): Int {
            if (value == null) return R.color.text_secondary
            return when {
                value == 0.0 -> R.color.metric_loss_ok
                value <= 5.0 -> R.color.metric_latency_warn
                else -> R.color.metric_loss_bad
            }
        }

        private fun jitterColor(value: Double?): Int {
            if (value == null) return R.color.metric_jitter
            return when {
                value <= 20 -> R.color.metric_jitter_ok
                value <= 50 -> R.color.metric_jitter_warn
                else -> R.color.metric_jitter_bad
            }
        }
    }
}

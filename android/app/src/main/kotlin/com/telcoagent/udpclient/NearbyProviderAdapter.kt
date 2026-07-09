package com.telcoagent.udpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.telcoagent.udpclient.databinding.ItemNearbyProviderBinding

class NearbyProviderAdapter(
    private val onProviderClick: (NearbyProvider) -> Unit,
) : RecyclerView.Adapter<NearbyProviderAdapter.ViewHolder>() {
    private val items = mutableListOf<NearbyProvider>()

    fun submitList(providers: List<NearbyProvider>) {
        items.clear()
        items.addAll(providers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNearbyProviderBinding.inflate(
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
        private val binding: ItemNearbyProviderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(provider: NearbyProvider) {
            val context = binding.root.context
            binding.root.isClickable = true
            binding.root.setOnClickListener { onProviderClick(provider) }
            binding.providerName.text = NearbyProviderFormatter.providerName(
                provider.operator,
                provider.operatorName,
            )

            val distance = provider.nearestKm?.let {
                context.getString(R.string.around_you_distance_km, it)
            } ?: context.getString(R.string.value_dash)
            val samples = context.getString(
                R.string.around_you_samples,
                provider.sampleCount,
                provider.sessionCount,
            )
            val lastSeen = NearbyProviderFormatter.formatLastSeen(provider.lastSeen)
            binding.providerMeta.text = context.getString(
                R.string.around_you_provider_meta,
                distance,
                samples,
                lastSeen,
            )

            binding.providerUplink.text = NearbyProviderFormatter.formatMs(provider.avgUdpUplinkMs)
            binding.providerDownlink.text = NearbyProviderFormatter.formatMs(provider.avgUdpDownlinkMs)
            binding.providerRtt.text = NearbyProviderFormatter.formatMs(provider.avgUdpLatencyMs)
            binding.providerLoss.text = NearbyProviderFormatter.formatPct(provider.avgUdpLossPct)
            binding.providerJitter.text = NearbyProviderFormatter.formatMs(provider.avgUdpJitterMs)

            binding.providerUplink.setTextColor(
                ContextCompat.getColor(context, latencyColor(provider.avgUdpUplinkMs)),
            )
            binding.providerDownlink.setTextColor(
                ContextCompat.getColor(context, latencyColor(provider.avgUdpDownlinkMs)),
            )
            binding.providerRtt.setTextColor(
                ContextCompat.getColor(context, latencyColor(provider.avgUdpLatencyMs)),
            )
            binding.providerLoss.setTextColor(
                ContextCompat.getColor(context, lossColor(provider.avgUdpLossPct)),
            )
            binding.providerJitter.setTextColor(
                ContextCompat.getColor(context, jitterColor(provider.avgUdpJitterMs)),
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

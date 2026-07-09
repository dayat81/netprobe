package com.telcoagent.udpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.telcoagent.udpclient.databinding.ItemFlowBinding

data class FlowEntry(
    val clientIp: String,
    val clientPort: Int,
    val serverIp: String,
    val serverPort: Int,
    val proto: String,
    val state: String,
    val dir: String,
    val uplink: Long,
    val downlink: Long,
)

class FlowAdapter : RecyclerView.Adapter<FlowAdapter.ViewHolder>() {
    private val items = mutableListOf<FlowEntry>()

    fun submitList(flows: List<FlowEntry>) {
        items.clear()
        items.addAll(flows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFlowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemFlowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(flow: FlowEntry) {
            // Server IP:port
            binding.flowServer.text = "${flow.serverIp}:${flow.serverPort}"

            // Protocol
            binding.flowProto.text = flow.proto.uppercase()

            // Uplink / Downlink
            binding.flowUplink.text = formatSize(flow.uplink)
            binding.flowDownlink.text = formatSize(flow.downlink)

            // Tuple: client → server
            val arrow = if (flow.dir == "outbound") "→" else "←"
            binding.flowTuple.text = "${flow.clientIp}:${flow.clientPort} $arrow ${flow.serverIp}:${flow.serverPort}"

            // State badge
            binding.flowState.text = flow.state.uppercase()
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
                bytes > 0 -> "$bytes B"
                else -> "—"
            }
        }
    }
}

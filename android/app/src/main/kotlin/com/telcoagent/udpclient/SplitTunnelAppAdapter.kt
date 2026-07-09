package com.telcoagent.udpclient

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.telcoagent.udpclient.databinding.ItemSplitTunnelAppBinding

data class SplitTunnelApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var selected: Boolean,
)

class SplitTunnelAppAdapter(
    private val onToggle: (SplitTunnelApp) -> Unit,
) : RecyclerView.Adapter<SplitTunnelAppAdapter.ViewHolder>() {

    private var allApps = listOf<SplitTunnelApp>()
    private var filteredApps = listOf<SplitTunnelApp>()
    private var filterQuery = ""

    fun submitApps(apps: List<SplitTunnelApp>) {
        allApps = apps
        applyFilter()
    }

    fun setFilter(query: String) {
        filterQuery = query.lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        filteredApps = if (filterQuery.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(filterQuery) ||
                    it.packageName.lowercase().contains(filterQuery)
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): Set<String> =
        allApps.filter { it.selected }.map { it.packageName }.toSet()

    fun setSelectedPackages(packages: Set<String>) {
        allApps.forEach { it.selected = it.packageName in packages }
        filteredApps.forEach { it.selected = it.packageName in packages }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = filteredApps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSplitTunnelAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    inner class ViewHolder(
        private val binding: ItemSplitTunnelAppBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: SplitTunnelApp) {
            binding.appIcon.setImageDrawable(app.icon)
            binding.appName.text = app.appName
            binding.appCheck.setOnCheckedChangeListener(null)
            binding.appCheck.isChecked = app.selected
            binding.appCheck.setOnCheckedChangeListener { _, _ ->
                app.selected = !app.selected
                onToggle(app)
            }
            binding.root.setOnClickListener {
                app.selected = !app.selected
                binding.appCheck.isChecked = app.selected
                onToggle(app)
            }
        }
    }
}

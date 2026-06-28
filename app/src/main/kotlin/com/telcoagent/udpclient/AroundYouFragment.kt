package com.telcoagent.udpclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.telcoagent.udpclient.databinding.BottomSheetNearbySessionsBinding
import com.telcoagent.udpclient.databinding.FragmentAroundYouBinding
import kotlinx.coroutines.launch

class AroundYouFragment : Fragment() {
    private var _binding: FragmentAroundYouBinding? = null
    private val binding get() = _binding!!
    private val api = NetProbeApi()
    private lateinit var adapter: NearbyProviderAdapter
    private var isLoading = false
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAroundYouBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = NearbyProviderAdapter { provider -> showProviderSessions(provider) }
        binding.aroundYouRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.aroundYouRecycler.adapter = adapter
        binding.aroundYouRefresh.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (isLoading) return
        if (!LocationReader.hasPermission(requireContext())) {
            showMessage(getString(R.string.around_you_location_permission_required))
            return
        }

        val (lat, lon) = LocationReader.read(requireContext())
        if (lat == null || lon == null) {
            showMessage(getString(R.string.around_you_location_unavailable))
            return
        }

        lastLat = lat
        lastLon = lon
        setLoading(true)
        binding.aroundYouLocation.text = getString(
            R.string.around_you_location,
            lat,
            lon,
            DEFAULT_RADIUS_KM,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val result = api.fetchNearbyProviders(lat, lon, DEFAULT_RADIUS_KM)
            if (_binding == null) return@launch
            setLoading(false)
            result.fold(
                onSuccess = { data ->
                    if (data.providers.isEmpty()) {
                        showMessage(getString(R.string.around_you_empty, data.radiusKm))
                    } else {
                        adapter.submitList(data.providers)
                        binding.aroundYouEmpty.visibility = View.GONE
                        binding.aroundYouRecycler.visibility = View.VISIBLE
                    }
                },
                onFailure = { error ->
                    showMessage(getString(R.string.around_you_error))
                    Snackbar.make(
                        binding.root,
                        error.message ?: getString(R.string.around_you_error),
                        Snackbar.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun showProviderSessions(provider: NearbyProvider) {
        val lat = lastLat ?: return
        val lon = lastLon ?: return
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetNearbySessionsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val title = NearbyProviderFormatter.providerName(provider.operator, provider.operatorName)
        sheetBinding.sheetTitle.text = getString(R.string.around_you_recent_sessions_title, title)
        sheetBinding.sheetSubtitle.text = getString(
            R.string.around_you_recent_sessions_subtitle,
            provider.sessionCount,
            DEFAULT_RADIUS_KM,
        )
        sheetBinding.sheetLoading.visibility = View.VISIBLE
        sheetBinding.sheetEmpty.visibility = View.GONE
        sheetBinding.sheetRecycler.visibility = View.GONE

        val sessionAdapter = NearbySessionAdapter()
        sheetBinding.sheetRecycler.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.sheetRecycler.adapter = sessionAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            val result = api.fetchNearbyProviderSessions(
                lat = lat,
                lon = lon,
                operator = provider.operator,
                radiusKm = DEFAULT_RADIUS_KM,
                limit = SESSION_LIMIT,
            )
            if (!dialog.isShowing) return@launch
            sheetBinding.sheetLoading.visibility = View.GONE
            result.fold(
                onSuccess = { data ->
                    if (data.sessions.isEmpty()) {
                        sheetBinding.sheetEmpty.visibility = View.VISIBLE
                        sheetBinding.sheetEmpty.text =
                            getString(R.string.around_you_recent_sessions_empty)
                    } else {
                        sheetBinding.sheetRecycler.visibility = View.VISIBLE
                        sessionAdapter.submitList(data.sessions)
                    }
                },
                onFailure = { error ->
                    sheetBinding.sheetEmpty.visibility = View.VISIBLE
                    sheetBinding.sheetEmpty.text = error.message ?: getString(R.string.around_you_error)
                },
            )
        }

        dialog.show()
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        binding.aroundYouLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.aroundYouRefresh.isEnabled = !loading
        if (loading) {
            binding.aroundYouRecycler.visibility = View.GONE
            binding.aroundYouEmpty.visibility = View.GONE
        }
    }

    private fun showMessage(message: String) {
        binding.aroundYouRecycler.visibility = View.GONE
        binding.aroundYouEmpty.visibility = View.VISIBLE
        binding.aroundYouEmpty.text = message
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val DEFAULT_RADIUS_KM = 5.0
        private const val SESSION_LIMIT = 10
    }
}

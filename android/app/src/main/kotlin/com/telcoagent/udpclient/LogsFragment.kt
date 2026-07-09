package com.telcoagent.udpclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.telcoagent.udpclient.databinding.FragmentLogsBinding
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {
    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: LogRecordStore
    private lateinit var adapter: LogRecordAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = LogRecordStore(requireContext())
        adapter = LogRecordAdapter { record -> retrySync(record) }
        binding.logsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.logsRecycler.adapter = adapter
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val records = store.refreshFromDisk()
        adapter.submitList(records)
        binding.logsEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.logsRecycler.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun retrySync(record: LogRecord) {
        viewLifecycleOwner.lifecycleScope.launch {
            Snackbar.make(binding.root, R.string.radio_log_uploading, Snackbar.LENGTH_SHORT).show()
            refreshList()
            val result = LogSyncHelper.sync(requireContext(), record.id)
            refreshList()
            result.fold(
                onSuccess = { sessionId ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.radio_log_uploaded, sessionId),
                        Snackbar.LENGTH_LONG,
                    ).show()
                },
                onFailure = { error ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.radio_log_upload_failed, error.message ?: "error"),
                        Snackbar.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

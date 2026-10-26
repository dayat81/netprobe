package com.aksa.magnon

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.aksa.magnon.databinding.ActivityMainBinding

/** AI tab views. Only the Lite layout has them. */
internal object PostChrome {
    var attached = false
    lateinit var tab: TextView
    lateinit var pane: ViewPager2
    lateinit var link: TextView
    lateinit var quota: TextView
    lateinit var device: TextView
    lateinit var copy: TextView
    lateinit var share: Button
    lateinit var preview: TextView
    lateinit var history: ScrollView
    lateinit var current: LinearLayout
    lateinit var historyList: LinearLayout
    lateinit var language: Spinner
    lateinit var style: Spinner
    lateinit var autoLabel: TextView
    lateinit var autoTag: Spinner
    lateinit var events: LinearLayout
    lateinit var draft: Button
    lateinit var verdict: TextView

    fun install(binding: ActivityMainBinding) {
        tab = binding.tabComplaint
        pane = binding.complaintPane
        link = binding.complaintLink
        quota = binding.complaintQuota
        device = binding.complaintDevice
        copy = binding.complaintCopy
        share = binding.complaintShare
        preview = binding.complaintPreview
        history = binding.complaintHistory
        current = binding.complaintCurrent
        historyList = binding.complaintHistoryList
        language = binding.complaintLanguage
        style = binding.complaintStyle
        autoLabel = binding.complaintAutoLabel
        autoTag = binding.complaintAutoTag
        events = binding.complaintEvents
        draft = binding.complaintDraft
        verdict = binding.complaintVerdict
        attached = true
    }
}

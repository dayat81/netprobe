package com.telcoagent.udpclient

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 8

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> AroundYouFragment()
            1 -> ProbeFragment()
            2 -> AbrFragment()
            3 -> MaxFragment()
            4 -> RadioFragment()
            5 -> AnalysisFragment()
            6 -> FlowFragment()
            else -> LogsFragment()
        }
    }
}

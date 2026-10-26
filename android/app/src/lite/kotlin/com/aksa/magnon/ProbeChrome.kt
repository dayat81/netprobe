package com.aksa.magnon

import com.aksa.magnon.databinding.ActivityMainBinding

/** Lite does not run radio, traceroute, gateway ping, or pcap. */
internal object ProbeChrome {
    fun install(activity: MainActivity, binding: ActivityMainBinding) = Unit

    fun start(activity: MainActivity) = Unit

    fun stop(activity: MainActivity) = Unit

    fun show(tab: String) = Unit

    fun onRound(activity: MainActivity) = Unit

    fun onVpnConsent(activity: MainActivity, granted: Boolean) = Unit
}

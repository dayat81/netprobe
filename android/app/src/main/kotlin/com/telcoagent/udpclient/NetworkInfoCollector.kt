package com.telcoagent.udpclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInfoSnapshot(
    val localIp: String? = null,
    val dnsServers: String? = null,
    val networkType: String? = null,
)

object NetworkInfoCollector {
    fun usesExternalProviderLookup(context: Context): Boolean {
        val networkType = read(context).networkType
        return networkType == "WIFI" || networkType == "ETHERNET"
    }

    fun read(context: Context): NetworkInfoSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return fromInterfaces()

        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val linkProps = network?.let { cm.getLinkProperties(it) }

        val networkType = resolveNetworkType(cm, caps)

        val ipv4 = linkProps?.linkAddresses
            ?.mapNotNull { la ->
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) addr.hostAddress else null
            }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: collectIpv4()

        val dns = linkProps?.dnsServers
            ?.mapNotNull { it.hostAddress?.takeIf { addr -> addr.isNotBlank() } }
            ?.distinct()
            ?.joinToString(";")
            ?.takeIf { it.isNotEmpty() }

        return NetworkInfoSnapshot(
            localIp = ipv4.joinToString(";"),
            dnsServers = dns,
            networkType = networkType,
        )
    }

    private fun resolveNetworkType(
        cm: ConnectivityManager,
        caps: NetworkCapabilities?,
    ): String {
        caps?.let { activeCaps ->
            return transportFromCaps(activeCaps)
        }

        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            cm.getNetworkCapabilities(activeNetwork)?.let { activeCaps ->
                return transportFromCaps(activeCaps)
            }
        }

        for (network in cm.allNetworks) {
            val networkCaps = cm.getNetworkCapabilities(network) ?: continue
            if (!networkCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI"
            if (networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET"
        }
        for (network in cm.allNetworks) {
            val networkCaps = cm.getNetworkCapabilities(network) ?: continue
            if (networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR"
        }
        return "UNKNOWN"
    }

    private fun transportFromCaps(caps: NetworkCapabilities): String {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
    }

    private fun fromInterfaces(): NetworkInfoSnapshot {
        val ipv4 = collectIpv4()
        return NetworkInfoSnapshot(
            localIp = ipv4.joinToString(";").takeIf { it.isNotEmpty() },
        )
    }

    private fun collectIpv4(): List<String> {
        return NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { ni ->
            if (!ni.isUp || ni.isLoopback) return@flatMap emptyList<String>()
            ni.inetAddresses.toList().mapNotNull { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress) addr.hostAddress else null
            }
        }?.distinct() ?: emptyList()
    }
}

package com.aksa.magnon

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Gateway and traceroute helpers. Compiled only into the premium flavor. */
object ClientTrace {
    private const val TAG = "MySignalGw"

    /** Last non-empty IPv4 gateway resolved on the IO path (for quick UI paint). */
    @Volatile
    var lastGateway: String? = null
        private set

    /** Last non-empty IPv6 gateway (global or link-local RA). */
    @Volatile
    var lastGatewayV6: String? = null
        private set

    /**
     * Default-route / first-hop **IPv4** gateway.
     *
     * Cellular often advertises `0.0.0.0/0 via <own IP>` (point-to-point). That is
     * rejected; we then prefer traceroute `-4` first hop (PingTools-style).
     * IPv6 is resolved separately via [gatewayIpv6].
     */
    fun gatewayIp(context: Context): String {
        val locals = localAddresses(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val iface = props?.interfaceName
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val p2p = isCellularP2P(props?.routes, locals)
        gatewayFromRoutesV4(props?.routes, locals)?.let { return rememberGateway(it, "routes") }
        gatewayFromWifiDhcp(context, locals)?.let { return rememberGateway(it, "dhcp") }
        gatewayFromIpRouteGetV4(locals, iface)?.let { return rememberGateway(it, "ip-route") }
        NativeTraceroute.firstRespondingHop(context)?.let { hop ->
            if (isUsableGateway(hop, locals) && !hop.contains(':')) {
                return rememberGateway(hop, "traceroute")
            }
        }
        CellGatewayProbe.firstHop(network, locals)?.let { hop ->
            if (!hop.contains(':')) return rememberGateway(hop, "icmp-ttl1")
        }
        firstHopFromTtlProbe(locals)?.let { hop ->
            if (!hop.contains(':')) return rememberGateway(hop, "shell-ttl1")
        }
        AppLog.i(
            TAG,
            "gateway v4 none locals=$locals iface=$iface cell=$cellular p2p=$p2p routes=${props?.routes?.map { routeSummary(it) }}",
        )
        return "—"
    }

    /**
     * Default-route / first-hop **IPv6** gateway (global or link-local).
     *
     * Same PingTools path as IPv4: `traceroute -6` to a public destination when
     * LinkProperties hides the RA next-hop behind `::/0 -> ::`.
     */
    fun gatewayIpv6(context: Context): String {
        val locals = localAddresses(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val iface = props?.interfaceName
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val p2p = isCellularP2P(props?.routes, locals)
        val hasLocalV6 = locals.any { it.contains(':') }
        gatewayFromRoutesV6(props?.routes, locals)?.let { return rememberGatewayV6(it, "routes") }
        gatewayFromIpRouteGetV6(locals, iface)?.let { return rememberGatewayV6(it, "ip-route") }
        if (hasLocalV6) {
            NativeTraceroute.firstRespondingHopV6(context)?.let { hop ->
                if (isUsableGateway(hop, locals) && hop.contains(':')) {
                    return rememberGatewayV6(hop, "traceroute6")
                }
            }
        }
        CellGatewayProbe.cellRaLinkLocal(cellular, p2p)?.let { return rememberGatewayV6(it, "cell-ra") }
        AppLog.i(
            TAG,
            "gateway v6 none locals=$locals iface=$iface cell=$cellular p2p=$p2p hasLocalV6=$hasLocalV6",
        )
        return "—"
    }

    /**
     * Route-table gateway for this moment, ignoring the cached hop.
     * Cellular point-to-point routes that name the phone itself are skipped.
     */
    fun freshGateways(context: Context): Pair<String, String> {
        val locals = localAddresses(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val props = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
        val v4 = gatewayFromRoutesV4(props?.routes, locals)
            ?: gatewayFromWifiDhcp(context, locals)
            ?: "—"
        val v6 = gatewayFromRoutesV6(props?.routes, locals) ?: "—"
        if (v4 != "—") rememberGateway(v4, "speed-route")
        if (v6 != "—") rememberGatewayV6(v6, "speed-route")
        return v4 to v6
    }

    /** First responding traceroute hop for the speedtest path. Replaces the route-table guess. */
    fun noteFirstHops(ipv4: String?, ipv6: String?) {
        ipv4?.takeIf { it.isNotBlank() && it != "—" && !it.contains(':') }?.let {
            rememberGateway(it, "speed-trace")
        }
        ipv6?.takeIf { it.isNotBlank() && it != "—" && it.contains(':') }?.let {
            rememberGatewayV6(it, "speed-trace")
        }
    }

    /** Sync snapshot used by UI before IO probe finishes. */
    fun gatewayIpQuick(context: Context): String {
        lastGateway?.takeIf { it.isNotBlank() && it != "—" }?.let { return it }
        val locals = localAddresses(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        gatewayFromRoutesV4(props?.routes, locals)?.let { return it }
        gatewayFromWifiDhcp(context, locals)?.let { return it }
        return "—"
    }

    fun gatewayIpv6Quick(context: Context): String {
        lastGatewayV6?.takeIf { it.isNotBlank() && it != "—" }?.let { return it }
        val locals = localAddresses(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        gatewayFromRoutesV6(props?.routes, locals)?.let { return it }
        return "—"
    }

    private fun rememberGateway(host: String, source: String): String {
        lastGateway = host
        AppLog.i(TAG, "gateway=$host via $source")
        return host
    }

    private fun rememberGatewayV6(host: String, source: String): String {
        lastGatewayV6 = host
        AppLog.i(TAG, "gateway6=$host via $source")
        return host
    }

    private fun routeSummary(route: RouteInfo): String {
        val dest = route.destination?.toString() ?: "?"
        val gw = route.gateway?.let { formatHost(it) } ?: "-"
        return "$dest->$gw"
    }

    private fun localAddresses(context: Context): Set<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptySet()
        val network = cm.activeNetwork ?: return emptySet()
        val props = cm.getLinkProperties(network) ?: return emptySet()
        return props.linkAddresses
            .mapNotNull { formatHost(it.address) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun gatewayFromRoutesV4(routes: List<RouteInfo>?, locals: Set<String>): String? {
        if (routes.isNullOrEmpty()) return null
        return routes.filter { it.isDefaultRoute && it.gateway != null }
            .mapNotNull { formatHost(it.gateway!!) }
            .filter { !it.contains(':') && it !in locals && it != "0.0.0.0" }
            .firstOrNull()
    }

    private fun gatewayFromRoutesV6(routes: List<RouteInfo>?, locals: Set<String>): String? {
        if (routes.isNullOrEmpty()) return null
        val candidates = routes.filter { it.isDefaultRoute && it.gateway != null }
            .mapNotNull { formatHost(it.gateway!!) }
            .filter { it.contains(':') && it !in locals && it != "::" }
        // Prefer global over link-local when both appear.
        candidates.firstOrNull { !it.startsWith("fe80:", ignoreCase = true) }?.let { return it }
        return candidates.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun gatewayFromWifiDhcp(context: Context, locals: Set<String>): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val dhcp = runCatching { wifi.dhcpInfo }.getOrNull() ?: return null
        val raw = dhcp.gateway
        if (raw == 0) return null
        val addr = InetAddress.getByAddress(
            byteArrayOf(
                (raw and 0xff).toByte(),
                (raw shr 8 and 0xff).toByte(),
                (raw shr 16 and 0xff).toByte(),
                (raw shr 24 and 0xff).toByte(),
            ),
        )
        val host = formatHost(addr) ?: return null
        return host.takeIf { it !in locals }
    }

    /**
     * Kernel next-hop from `ip route get`. LinkProperties hides cellular RA
     * gateways (`fe80::1`) behind `::/0 -> ::`.
     */
    private fun gatewayFromIpRouteGetV4(locals: Set<String>, iface: String?): String? =
        firstViaFromIp(
            locals,
            listOf(
                arrayOf("ip", "route", "get", "8.8.8.8"),
                arrayOf("ip", "route", "show", "default"),
            ),
            ipv6 = false,
        )

    private fun gatewayFromIpRouteGetV6(locals: Set<String>, iface: String?): String? =
        firstViaFromIp(
            locals,
            listOf(
                arrayOf("ip", "-6", "route", "get", "2001:4860:4860::8888"),
                arrayOf("ip", "-6", "route", "show", "default"),
                arrayOf("ip", "-6", "route", "show", "table", "all"),
            ),
            ipv6 = true,
        )

    private fun firstViaFromIp(
        locals: Set<String>,
        cmds: List<Array<String>>,
        ipv6: Boolean,
    ): String? {
        for (cmd in cmds) {
            val out = runShell(cmd) ?: continue
            for (match in VIA_RE.findAll(out)) {
                val via = match.groupValues[1].substringBefore('%')
                if (!isUsableGateway(via, locals)) continue
                if (ipv6 != via.contains(':')) continue
                return via
            }
        }
        return null
    }

    private fun isUsableGateway(host: String, locals: Set<String>): Boolean {
        if (host.isBlank()) return false
        if (host in locals) return false
        if (host == "0.0.0.0" || host == "::" || host == "::1") return false
        return true
    }

    /** Cell P2P: IPv4 default via own address and/or IPv6 default via `::`. */
    private fun isCellularP2P(routes: List<RouteInfo>?, locals: Set<String>): Boolean {
        if (routes.isNullOrEmpty()) return false
        val defaults = routes.filter { it.isDefaultRoute }
        if (defaults.isEmpty()) return false
        var viaSelf = false
        var viaUnspec = false
        for (route in defaults) {
            val gw = route.gateway ?: continue
            val host = formatHost(gw) ?: continue
            when (gw) {
                is Inet4Address -> if (host in locals) viaSelf = true
                is Inet6Address -> if (gw.isAnyLocalAddress || host == "::") viaUnspec = true
            }
        }
        return viaSelf || viaUnspec
    }

    /**
     * Shell `ping -t 1` (PingTools ICMP path). App UID often gets
     * Operation not permitted; kept as fallback after Os ICMP sockets.
     */
    private fun firstHopFromTtlProbe(locals: Set<String>): String? {
        val targets = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
        for (target in targets) {
            val hop = ttl1FirstHop(target) ?: continue
            if (isUsableGateway(hop, locals)) return hop
        }
        return null
    }

    private fun ttl1FirstHop(target: String): String? {
        val variants = listOf(
            arrayOf("ping", "-c", "1", "-t", "1", "-W", "1", target),
            arrayOf("ping", "-c", "1", "-t", "1", "-w", "1", target),
            arrayOf("ping6", "-c", "1", "-t", "1", "-W", "1", target),
        )
        for (cmd in variants) {
            val out = runShell(cmd, timeoutSec = 3) ?: continue
            FROM_RE.find(out)?.groupValues?.getOrNull(1)?.let { return it.substringBefore('%') }
            FROM_TTL_EXCEEDED_RE.find(out)?.groupValues?.getOrNull(1)
                ?.let { return it.substringBefore('%') }
        }
        return null
    }

    private fun runShell(cmd: Array<String>, timeoutSec: Long = 2): String? {
        return try {
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                null
            } else {
                out
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "shell ${cmd.joinToString(" ")}: ${e.message}")
            null
        }
    }

    private val VIA_RE = Regex("""(?i)\bvia\s+([0-9a-fA-F:.]+)""")
    private val FROM_RE = Regex("""(?i)(?:From|from)\s+([0-9a-fA-F:.]+)""")
    /** PingTools-style: `From a.b.c.d: icmp_seq=N Time to live exceeded` */
    private val FROM_TTL_EXCEEDED_RE =
        Regex("""(?i)From\s+(\S+?):?\s+icmp_seq=\d+\s+Time to live exceeded""")

    /** Prefer global, then ULA/site, then link-local. */
    private fun ipv6Rank(addr: Inet6Address): Int = when {
        !addr.isLinkLocalAddress && !addr.isSiteLocalAddress && !addr.isMulticastAddress -> 0
        addr.isSiteLocalAddress -> 1
        addr.isLinkLocalAddress -> 2
        else -> 3
    }

    private fun formatHost(addr: InetAddress): String? {
        val raw = addr.hostAddress ?: return null
        val bare = raw.substringBefore('%')
        return bare.ifEmpty { null }
    }

}

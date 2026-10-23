package com.aksa.mysignal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PublicClientInfo(
    val ip: String,
    val provider: String,
)

/** Local interface addresses and public IP from speedtest hello. */
object ClientNetworkInfo {
    private const val TAG = "MySignalGw"
    private val providerCache = ConcurrentHashMap<String, String>()

    @Volatile
    var lastPublic: PublicClientInfo? = null
        private set

    /** Last non-empty gateway resolved on the IO path (for quick UI paint). */
    @Volatile
    var lastGateway: String? = null
        private set

    /** Last resolved ISP/provider name from public IP (empty if unknown). */
    fun lastProvider(): String =
        lastPublic?.provider?.takeIf { it.isNotBlank() && it != "—" }.orEmpty()

    /** Short brand names for common Indonesian (and nearby) ASNs. */
    private val asnBrand = mapOf(
        "23693" to "Telkomsel",
        "4761" to "Indosat",
        "4795" to "Indosat",
        "9340" to "Indosat",
        "24203" to "XL",
        "45727" to "Tri",
        "18004" to "Smartfren",
        "55666" to "Smartfren",
        "7713" to "Telkom",
        "17974" to "Telkom",
        "38757" to "ICON+",
        "58495" to "Biznet",
        "9905" to "Linknet",
        "23947" to "Moratel",
        "133840" to "MyRepublic",
        "64315" to "ASIA",
    )

    fun localSummary(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "—"
        val network = cm.activeNetwork ?: return "—"
        val props: LinkProperties = cm.getLinkProperties(network) ?: return "—"
        val addrs = props.linkAddresses.map { it.address }.filter { !it.isLoopbackAddress }

        val ipv4 = addrs
            .filterIsInstance<Inet4Address>()
            .mapNotNull { formatHost(it) }
            .firstOrNull()

        val ipv6 = addrs
            .filterIsInstance<Inet6Address>()
            .sortedWith(compareBy({ ipv6Rank(it) }, { it.hostAddress.orEmpty() }))
            .mapNotNull { formatHost(it) }
            .distinct()
            .firstOrNull()

        return when {
            ipv4 != null && ipv6 != null -> "$ipv4 / $ipv6"
            ipv4 != null -> ipv4
            ipv6 != null -> ipv6
            else -> "—"
        }
    }

    /**
     * Default-route / first-hop gateway for the active network.
     *
     * Cellular often advertises `0.0.0.0/0 via <own IP>` (point-to-point). That is
     * rejected; we then prefer ICMP TTL=1 first hop (PingTools-style), then the
     * common RA next-hop `fe80::1` when the route table is P2P/`::`.
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
        // LinkProperties often has cell IPv4 via=self and IPv6 via=::; kernel still
        // knows the RA next-hop (fe80::1). Prefer API routes, then probes.
        gatewayFromRoutes(props?.routes, locals)?.let { return rememberGateway(it, "routes") }
        gatewayFromWifiDhcp(context, locals)?.let { return rememberGateway(it, "dhcp") }
        gatewayFromIpRouteGet(locals, iface)?.let { return rememberGateway(it, "ip-route") }
        // PingTools path: bundled traceroute ELF → first responding hop (cell often * then 10.x).
        NativeTraceroute.firstRespondingHop(context)?.let { hop ->
            if (isUsableGateway(hop, locals)) return rememberGateway(hop, "traceroute")
        }
        CellGatewayProbe.firstHop(network, locals)?.let { return rememberGateway(it, "icmp-ttl1") }
        firstHopFromTtlProbe(locals)?.let { return rememberGateway(it, "shell-ttl1") }
        CellGatewayProbe.cellRaLinkLocal(cellular, p2p)?.let { return rememberGateway(it, "cell-ra") }
        Log.i(
            TAG,
            "gateway none locals=$locals iface=$iface cell=$cellular p2p=$p2p routes=${props?.routes?.map { routeSummary(it) }}",
        )
        return "—"
    }

    /** Sync snapshot used by UI before IO probe finishes. */
    fun gatewayIpQuick(context: Context): String {
        lastGateway?.takeIf { it.isNotBlank() && it != "—" }?.let { return it }
        val locals = localAddresses(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        gatewayFromRoutes(props?.routes, locals)?.let { return it }
        gatewayFromWifiDhcp(context, locals)?.let { return it }
        return "—"
    }

    private fun rememberGateway(host: String, source: String): String {
        lastGateway = host
        Log.i(TAG, "gateway=$host via $source")
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

    private fun gatewayFromRoutes(routes: List<RouteInfo>?, locals: Set<String>): String? {
        if (routes.isNullOrEmpty()) return null
        val defaults = routes.filter { it.isDefaultRoute && it.gateway != null }
        // Prefer a next-hop that is not our own address (cell P2P often sets via=self).
        val candidates = defaults.mapNotNull { formatHost(it.gateway!!) }
            .filter { it !in locals && it != "0.0.0.0" && it != "::" }
        // Prefer IPv4, then link-local IPv6 (common cell RA gateway fe80::1).
        candidates.firstOrNull { !it.contains(':') }?.let { return it }
        candidates.firstOrNull { it.startsWith("fe80:", ignoreCase = true) }?.let { return it }
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
    private fun gatewayFromIpRouteGet(locals: Set<String>, iface: String?): String? {
        val probes = listOf(
            arrayOf("ip", "-6", "route", "get", "2001:4860:4860::8888"),
            arrayOf("ip", "route", "get", "8.8.8.8"),
        )
        for (cmd in probes) {
            val out = runShell(cmd) ?: continue
            val via = VIA_RE.find(out)?.groupValues?.getOrNull(1)?.substringBefore('%') ?: continue
            if (!isUsableGateway(via, locals)) continue
            // Prefer hops on the active iface when the line reports one.
            if (iface != null && " dev " in out && " dev $iface" !in out && "dev $iface" !in out) {
                // Still accept: multi-table routing may omit matching iface text.
            }
            return via
        }
        // Explicit default lines (all tables).
        for (cmd in listOf(
            arrayOf("ip", "-6", "route", "show", "default"),
            arrayOf("ip", "route", "show", "default"),
            arrayOf("ip", "-6", "route", "show", "table", "all"),
        )) {
            val out = runShell(cmd) ?: continue
            for (match in VIA_RE.findAll(out)) {
                val via = match.groupValues[1].substringBefore('%')
                if (isUsableGateway(via, locals)) return via
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
            Log.w(TAG, "shell ${cmd.joinToString(" ")}: ${e.message}")
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

    fun publicIpFromHello(target: SpeedtestTarget): String = publicClientFromHello(target).ip

    fun publicClientFromHello(target: SpeedtestTarget): PublicClientInfo {
        val ip = fetchPublicIp(target)
        val info = if (ip == "—") {
            PublicClientInfo(ip = "—", provider = "—")
        } else {
            PublicClientInfo(ip = ip, provider = providerForPublicIp(ip))
        }
        lastPublic = info
        return info
    }

    private fun fetchPublicIp(target: SpeedtestTarget): String {
        val url = SpeedtestEngine.helloUrl(target.root())
        val connection = SpeedtestHttps.open(target, url, "GET").apply {
            setRequestProperty("Connection", "close")
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            connection.connect()
            val header = connection.getHeaderField("X-Client-IP")?.trim().orEmpty()
            val headerIsp = connection.getHeaderField("X-Client-ISP")?.trim().orEmpty()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) return "—"
            val jsonLine = body.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            val json = jsonLine?.let { runCatching { JSONObject(it) }.getOrNull() }
            val ip = header.ifEmpty { json?.optString("client_ip", "")?.trim().orEmpty() }
                .ifEmpty { return "—" }
            val fromHello = headerIsp.ifEmpty {
                json?.optString("client_isp", "")?.trim().orEmpty()
                    .ifEmpty { json?.optString("client_org", "")?.trim().orEmpty() }
            }
            if (fromHello.isNotEmpty()) {
                providerCache[ip] = prettyProvider(fromHello)
            }
            return ip
        } catch (_: Exception) {
            return "—"
        } finally {
            connection.disconnect()
        }
    }

    fun providerForPublicIp(ip: String): String {
        if (ip.isBlank() || ip == "—") return "—"
        providerCache[ip]?.let { return it }
        val org = lookupIpinfoOrg(ip) ?: return "—".also { providerCache[ip] = it }
        val pretty = prettyProvider(org)
        providerCache[ip] = pretty
        return pretty
    }

    private fun prettyProvider(org: String): String {
        val trimmed = org.trim()
        if (trimmed.isEmpty()) return "—"
        val asnMatch = Regex("""(?i)^AS(\d+)\s+(.*)$""").matchEntire(trimmed)
        val asn = asnMatch?.groupValues?.getOrNull(1)
        val rest = asnMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
        asnBrand[asn]?.let { return it }
        if (rest.isNotEmpty()) return shortenOrg(rest)
        return shortenOrg(trimmed.removePrefix("AS").trimStart { it.isDigit() || it.isWhitespace() })
    }

    private fun shortenOrg(name: String): String {
        val cleaned = name
            .replace(Regex("""(?i)\b(pt\.?|tbk\.?|ltd\.?|inc\.?|asn|as-id|indonesia)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isEmpty()) return name.trim().take(40)
        // Prefer known substrings in long legal names.
        val lower = cleaned.lowercase()
        return when {
            "telkomsel" in lower || "telekomunikasi selular" in lower -> "Telkomsel"
            "indosat" in lower || "im3" in lower -> "Indosat"
            "excelcom" in lower || "axiata" in lower || cleaned.startsWith("XL", ignoreCase = true) -> "XL"
            "hutchison" in lower || "three" in lower || Regex("""\btri\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Tri"
            "smartfren" in lower || "wireless indonesia" in lower -> "Smartfren"
            "telkom" in lower -> "Telkom"
            else -> cleaned.take(40)
        }
    }

    private fun lookupIpinfoOrg(ip: String): String? {
        val encoded = ip.replace("%", "%25")
        val connection = (URL("https://ipinfo.io/$encoded/json").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MySignal/1.0")
            connectTimeout = 4_000
            readTimeout = 4_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val org = JSONObject(body).optString("org", "").trim()
            return org.ifEmpty { null }
        } catch (_: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }
}

package com.aksa.mysignal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class PublicClientInfo(
    val ip: String,
    val provider: String,
)

/** Local interface addresses and public IP from speedtest hello. */
object ClientNetworkInfo {
    private val providerCache = ConcurrentHashMap<String, String>()

    @Volatile
    var lastPublic: PublicClientInfo? = null
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

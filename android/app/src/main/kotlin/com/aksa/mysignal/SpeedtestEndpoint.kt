package com.aksa.mysignal

import android.net.DnsResolver
import android.os.CancellationSignal
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class IpFamily {
    IPV4,
    IPV6,
    ;

    val label: String
        get() = when (this) {
            IPV4 -> "IPv4"
            IPV6 -> "IPv6"
        }

    fun next(): IpFamily = when (this) {
        IPV4 -> IPV6
        IPV6 -> IPV4
    }
}

/** Hostname + forced A/AAAA peer for HTTPS speedtest (literal IP URL, SNI = hostname). */
data class SpeedtestTarget(
    val family: IpFamily,
    val hostname: String,
    val serverIp: String,
    val pathPrefix: String,
) {
    val serverLabel: String
        get() = "$hostname · ${family.label} · $serverIp"

    fun root(): String {
        val host = if (family == IpFamily.IPV6) "[$serverIp]" else serverIp
        return "https://$host${pathPrefix.trimEnd('/')}"
    }

    companion object {
        private const val TAG = "MySignalSpeed"

        /** RFC 6052 well-known NAT64 prefix — DNS64 synthetics, not native AAAA. */
        private val NAT64_PREFIX = byteArrayOf(0x00, 0x64, 0xff.toByte(), 0x9b.toByte())

        fun resolve(
            hostname: String = BuildConfig.COLLECTOR_HOST,
            pathPrefix: String = BuildConfig.SPEEDTEST_PATH,
            family: IpFamily,
        ): SpeedtestTarget {
            val picked = when (family) {
                IpFamily.IPV4 -> {
                    InetAddress.getAllByName(hostname)
                        .firstOrNull { it is Inet4Address }
                        ?: throw IllegalStateException("no IPv4 for $hostname")
                }
                IpFamily.IPV6 -> pickNativeIpv6(hostname)
            }
            val ip = picked.hostAddress?.substringBefore('%')
                ?: throw IllegalStateException("empty ${family.label} for $hostname")
            return SpeedtestTarget(family, hostname, ip, pathPrefix)
        }

        /** Prefer real AAAA; skip DNS64/NAT64 64:ff9b::/96. */
        private fun pickNativeIpv6(hostname: String): Inet6Address {
            val fromGetAll = InetAddress.getAllByName(hostname)
                .filterIsInstance<Inet6Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !isNat64(it) }
            if (fromGetAll.isNotEmpty()) {
                Log.i(TAG, "IPv6 native via getAllByName ${formatIp(fromGetAll.first())}")
                return fromGetAll.first()
            }
            val fromAaaa = queryAaaa(hostname)
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !isNat64(it) }
            if (fromAaaa.isNotEmpty()) {
                Log.i(TAG, "IPv6 native via TYPE_AAAA ${formatIp(fromAaaa.first())}")
                return fromAaaa.first()
            }
            val nat64Only = InetAddress.getAllByName(hostname)
                .filterIsInstance<Inet6Address>()
                .any { isNat64(it) }
            if (nat64Only) {
                throw IllegalStateException("no native AAAA (DNS64/NAT64 only)")
            }
            throw IllegalStateException("no native IPv6 for $hostname")
        }

        private fun isNat64(addr: Inet6Address): Boolean {
            val b = addr.address ?: return false
            if (b.size < 4) return false
            return b[0] == NAT64_PREFIX[0] &&
                b[1] == NAT64_PREFIX[1] &&
                b[2] == NAT64_PREFIX[2] &&
                b[3] == NAT64_PREFIX[3]
        }

        private fun formatIp(addr: Inet6Address): String =
            addr.hostAddress?.substringBefore('%') ?: addr.toString()

        private fun queryAaaa(hostname: String): List<Inet6Address> {
            val latch = CountDownLatch(1)
            val answers = CopyOnWriteArrayList<InetAddress>()
            val error = AtomicReference<Exception>()
            val signal = CancellationSignal()
            try {
                DnsResolver.getInstance().query(
                    /* network = */ null,
                    hostname,
                    DnsResolver.TYPE_AAAA,
                    DnsResolver.FLAG_EMPTY,
                    Executors.newSingleThreadExecutor(),
                    signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            answers.addAll(answer)
                            latch.countDown()
                        }

                        override fun onError(e: DnsResolver.DnsException) {
                            error.set(e)
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "TYPE_AAAA query failed to start", e)
                return emptyList()
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                signal.cancel()
                Log.w(TAG, "TYPE_AAAA query timeout for $hostname")
                return emptyList()
            }
            error.get()?.let { Log.w(TAG, "TYPE_AAAA error for $hostname", it) }
            return answers.filterIsInstance<Inet6Address>()
        }
    }
}

object SpeedtestHttps {
    fun open(target: SpeedtestTarget, absoluteUrl: String, method: String): HttpsURLConnection {
        val connection = URL(absoluteUrl).openConnection() as HttpsURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 60_000
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Host", target.hostname)
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "keep-alive")
        connection.sslSocketFactory = SniSslSocketFactory(target.hostname)
        val expected = target.hostname
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(expected, session)
        }
        return connection
    }

    /** One TLS socket to the forced peer IP (SNI = hostname) for keep-alive HTTP ping. */
    fun openSocket(target: SpeedtestTarget, port: Int = 443): SSLSocket {
        val addr = InetAddress.getByName(target.serverIp)
        val socket = SniSslSocketFactory(target.hostname).createSocket(addr, port) as SSLSocket
        socket.tcpNoDelay = true
        socket.soTimeout = 12_000
        socket.startHandshake()
        return socket
    }

    private class SniSslSocketFactory(
        private val peerHost: String,
    ) : SSLSocketFactory() {
        private val delegate: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) =
            configure(delegate.createSocket(s, peerHost, port, autoClose))

        override fun createSocket(host: String, port: Int) =
            configure(delegate.createSocket(peerHost, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
            configure(delegate.createSocket(peerHost, port, localHost, localPort))

        override fun createSocket(address: InetAddress, port: Int) =
            configure(delegate.createSocket(address, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ) = configure(delegate.createSocket(address, port, localAddress, localPort))

        private fun configure(socket: java.net.Socket): java.net.Socket {
            val ssl = socket as? SSLSocket ?: return socket
            try {
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(peerHost))
                ssl.sslParameters = params
            } catch (_: Exception) {
            }
            return ssl
        }
    }
}

package com.aksa.mysignal

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.util.Log
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * First-hop discovery when LinkProperties hides the cell next-hop
 * (`0.0.0.0/0 via self`, `::/0 via ::`).
 *
 * Uses unprivileged ICMP datagram sockets (Android `IPPROTO_ICMP` /
 * `IPPROTO_ICMPV6`) with TTL/hop-limit 1 — same idea as PingTools'
 * traceroute/ICMP path, without shipping a native binary.
 */
object CellGatewayProbe {
    private const val TAG = "MySignalGw"
    private const val RECV_TIMEOUT_MS = 1_200L

    fun firstHop(network: Network?, locals: Set<String>): String? {
        for (target in listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")) {
            probeIcmp4(network, target)?.let { hop ->
                if (isUsable(hop, locals)) {
                    Log.i(TAG, "ttl1 icmp4 hop=$hop via $target")
                    return hop
                }
            }
        }
        for (target in listOf("2001:4860:4860::8888", "2606:4700:4700::1111")) {
            probeIcmp6(network, target)?.let { hop ->
                if (isUsable(hop, locals)) {
                    Log.i(TAG, "ttl1 icmp6 hop=$hop via $target")
                    return hop
                }
            }
        }
        return null
    }

    /**
     * Cellular RA next-hop is almost always `fe80::1` when IPv4 is P2P
     * (via=self) and IPv6 default is unspecified (`::`). Confirmed via
     * kernel `ip -6 route get` on device; app UID cannot read netlink/`proc`.
     */
    fun cellRaLinkLocal(isCellular: Boolean, p2pStyle: Boolean): String? {
        if (!isCellular || !p2pStyle) return null
        return "fe80::1"
    }

    private fun probeIcmp4(network: Network?, targetHost: String): String? {
        val dest = runCatching { InetAddress.getByName(targetHost) }.getOrNull() as? Inet4Address
            ?: return null
        var fd: FileDescriptor? = null
        return try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
            network?.bindSocket(fd)
            Os.setsockoptTimeval(
                fd,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(RECV_TIMEOUT_MS),
            )
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, 1)
            val id = Random.nextInt(1, 0xffff)
            val packet = buildIcmp4Echo(id, 1)
            Os.sendto(fd, packet, 0, packet.size, 0, dest, 0)
            recvHopAddress(fd)
        } catch (e: ErrnoException) {
            Log.d(TAG, "icmp4 $targetHost: ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "icmp4 $targetHost: ${e.message}")
            null
        } finally {
            closeQuietly(fd)
        }
    }

    private fun probeIcmp6(network: Network?, targetHost: String): String? {
        val dest = runCatching { InetAddress.getByName(targetHost) }.getOrNull() as? Inet6Address
            ?: return null
        var fd: FileDescriptor? = null
        return try {
            fd = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMPV6)
            network?.bindSocket(fd)
            Os.setsockoptTimeval(
                fd,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(RECV_TIMEOUT_MS),
            )
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS, 1)
            val id = Random.nextInt(1, 0xffff)
            val packet = buildIcmp6Echo(id, 1)
            Os.sendto(fd, packet, 0, packet.size, 0, dest, 0)
            recvHopAddress(fd)
        } catch (e: ErrnoException) {
            Log.d(TAG, "icmp6 $targetHost: ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "icmp6 $targetHost: ${e.message}")
            null
        } finally {
            closeQuietly(fd)
        }
    }

    private fun recvHopAddress(fd: FileDescriptor): String? {
        val buf = ByteArray(512)
        // Out-param: Android Os.recvfrom fills peer address into this holder.
        val peer = InetSocketAddress(0)
        val n = Os.recvfrom(fd, buf, 0, buf.size, 0, peer)
        if (n <= 0) return null
        val host = peer.address?.hostAddress?.substringBefore('%') ?: return null
        // Echo reply from destination (TTL still reached) is not a gateway.
        if (isIcmpEchoReply(buf, n)) return null
        return host.ifEmpty { null }
    }

    private fun isIcmpEchoReply(buf: ByteArray, n: Int): Boolean {
        if (n < 1) return false
        // ICMPv4 echo reply = 0; ICMPv6 echo reply = 129
        return buf[0] == 0.toByte() || buf[0] == 129.toByte()
    }

    private fun buildIcmp4Echo(id: Int, seq: Int): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.put(8.toByte()) // echo request
        buf.put(0.toByte())
        buf.putShort(0) // checksum
        buf.putShort(id.toShort())
        buf.putShort(seq.toShort())
        buf.putLong(System.nanoTime())
        val arr = buf.array()
        val sum = checksum(arr)
        arr[2] = (sum ushr 8).toByte()
        arr[3] = (sum and 0xff).toByte()
        return arr
    }

    private fun buildIcmp6Echo(id: Int, seq: Int): ByteArray {
        // Kernel fills ICMPv6 checksum for SOCK_DGRAM.
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.put(128.toByte()) // echo request
        buf.put(0.toByte())
        buf.putShort(0)
        buf.putShort(id.toShort())
        buf.putShort(seq.toShort())
        buf.putLong(System.nanoTime())
        return buf.array()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < data.size) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    private fun isUsable(host: String, locals: Set<String>): Boolean {
        if (host.isBlank()) return false
        if (host in locals) return false
        if (host == "0.0.0.0" || host == "::" || host == "::1") return false
        return true
    }

    private fun closeQuietly(fd: FileDescriptor?) {
        if (fd == null) return
        try {
            Os.close(fd)
        } catch (_: Exception) {
        }
    }
}

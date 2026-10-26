package com.aksa.magnon

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Forwards one app's TCP and UDP through sockets pinned to the upstream
 * network and writes the replies back to the TUN. ICMP is left unanswered.
 */
internal class PcapRelay(
    private val outsideTcp: (Socket) -> Unit,
    private val outsideUdp: (DatagramSocket) -> Unit,
    private val writeTun: (ByteArray) -> Unit,
) {
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pcap-flow").apply { isDaemon = true }
    }
    private val tcp = HashMap<String, TcpLeg>()
    private val udp = HashMap<String, UdpLeg>()
    private val mapLock = Any()

    @Volatile
    private var closed = false

    fun onPacket(packet: ByteArray) {
        if (closed) return
        try {
            val parsed = IpPackets.parse(packet) ?: return
            if (parsed.srcPort == 0 || parsed.dstPort == 0) return
            when (parsed.proto) {
                6 -> onTcp(parsed)
                17 -> onUdp(parsed)
            }
        } catch (error: Exception) {
            if (!closed) AppLog.w(TAG, "packet: ${error.message}")
        }
    }

    fun close() {
        closed = true
        val sockets = synchronized(mapLock) {
            val all = tcp.values.map { it.socket } + udp.values.map { it.socket }
            tcp.clear()
            udp.clear()
            all
        }
        sockets.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun onTcp(parsed: IpDatagram) {
        val key = key(parsed)
        val syn = parsed.flags and 0x02 != 0
        val existing = synchronized(mapLock) { tcp[key] }
        if (existing == null) {
            if (!syn || parsed.flags and 0x10 != 0) return
            if (synchronized(mapLock) { tcp.size + udp.size } >= MAX_FLOWS) return
            openTcp(key, parsed)
            return
        }
        val outbound = synchronized(existing) {
            if (!existing.up) {
                if (syn) existing.synAck?.let { writeTun(it) }
                else if (parsed.payload.isNotEmpty() && existing.early.size < 8) existing.early.add(parsed.payload.copyOf())
                return
            }
            takeTcp(existing, parsed)
        }
        if (outbound != null && outbound.isNotEmpty()) {
            runCatching { existing.socket.getOutputStream().write(outbound) }
        }
        if (parsed.flags and 0x01 != 0) runCatching { existing.socket.shutdownOutput() }
    }

    private fun openTcp(key: String, parsed: IpDatagram) {
        val socket = Socket()
        val leg = TcpLeg(
            socket = socket,
            client = parsed.src,
            clientPort = parsed.srcPort,
            server = parsed.dst,
            serverPort = parsed.dstPort,
            v6 = parsed.version == 6,
            clientNext = (parsed.seq + 1) and 0xffffffffL,
        )
        if (parsed.payload.isNotEmpty()) leg.early.add(parsed.payload.copyOf())
        synchronized(mapLock) {
            if (closed || tcp.containsKey(key)) {
                socket.close()
                return
            }
            tcp[key] = leg
        }
        pool.execute {
            try {
                outsideTcp(socket)
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address(parsed.dst), parsed.dstPort), 8_000)
                val isn = System.nanoTime() and 0xffffffffL
                val synAck = replyTcp(leg, isn, leg.clientNext, 0x12, ByteArray(0))
                synchronized(leg) {
                    leg.serverSeq = (isn + 1) and 0xffffffffL
                    leg.synAck = synAck
                    writeTun(synAck)
                    leg.up = true
                    while (leg.early.isNotEmpty()) {
                        val payload = leg.early.removeFirst()
                        socket.getOutputStream().write(payload)
                        leg.clientNext = (leg.clientNext + payload.size) and 0xffffffffL
                    }
                    sendAck(leg)
                }
                readTcp(key, leg)
            } catch (error: Exception) {
                if (!closed) AppLog.w(TAG, "tcp ${parsed.dstPort}: ${error.message}")
                synchronized(leg) { sendRst(leg) }
                dropTcp(key, socket)
            }
        }
    }

    /** Caller holds [leg]. Returns payload that must be written outside the lock. */
    private fun takeTcp(leg: TcpLeg, parsed: IpDatagram): ByteArray? {
        if (parsed.flags and 0x04 != 0) {
            runCatching { leg.socket.close() }
            return null
        }
        if (parsed.seq != leg.clientNext) {
            sendAck(leg)
            return null
        }
        val step = parsed.payload.size + if (parsed.flags and 0x01 != 0) 1 else 0
        if (step != 0) leg.clientNext = (leg.clientNext + step) and 0xffffffffL
        sendAck(leg)
        return parsed.payload.takeIf { it.isNotEmpty() }
    }

    private fun readTcp(key: String, leg: TcpLeg) {
        val input = leg.socket.getInputStream()
        val buf = ByteArray(1200)
        while (!closed) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            synchronized(leg) {
                val pkt = replyTcp(leg, leg.serverSeq, leg.clientNext, 0x18, buf.copyOf(n))
                leg.serverSeq = (leg.serverSeq + n) and 0xffffffffL
                writeTun(pkt)
            }
        }
        synchronized(leg) {
            val fin = replyTcp(leg, leg.serverSeq, leg.clientNext, 0x11, ByteArray(0))
            leg.serverSeq = (leg.serverSeq + 1) and 0xffffffffL
            writeTun(fin)
        }
        dropTcp(key, leg.socket)
    }

    private fun onUdp(parsed: IpDatagram) {
        val key = key(parsed)
        val existing = synchronized(mapLock) { udp[key] }
        if (existing != null) {
            sendUdp(key, existing, parsed)
            return
        }
        if (synchronized(mapLock) { tcp.size + udp.size } >= MAX_FLOWS) return
        val socket = DatagramSocket()
        val leg = UdpLeg(socket, parsed.src, parsed.srcPort, parsed.dst, parsed.dstPort, parsed.version == 6)
        try {
            outsideUdp(socket)
            socket.connect(address(parsed.dst), parsed.dstPort)
            socket.soTimeout = 30_000
            socket.send(DatagramPacket(parsed.payload, parsed.payload.size))
        } catch (error: Exception) {
            socket.close()
            if (!closed) AppLog.w(TAG, "udp ${parsed.dstPort}: ${error.message}")
            return
        }
        synchronized(mapLock) {
            if (closed || udp.containsKey(key)) {
                socket.close()
                return
            }
            udp[key] = leg
        }
        pool.execute {
            try {
                val buf = ByteArray(2000)
                val incoming = DatagramPacket(buf, buf.size)
                while (!closed) {
                    incoming.length = buf.size
                    socket.receive(incoming)
                    val pkt = IpPackets.udpSegment(
                        srcIp = leg.server,
                        dstIp = leg.client,
                        v6 = leg.v6,
                        srcPort = leg.serverPort,
                        dstPort = leg.clientPort,
                        payload = buf.copyOf(incoming.length),
                    )
                    writeTun(pkt)
                }
            } catch (error: Exception) {
                if (!closed) AppLog.d(TAG, "udp ${parsed.dstPort} closed")
            } finally {
                synchronized(mapLock) { if (udp[key] === leg) udp.remove(key) }
                runCatching { socket.close() }
            }
        }
    }

    private fun sendUdp(key: String, leg: UdpLeg, parsed: IpDatagram) {
        try {
            leg.socket.send(DatagramPacket(parsed.payload, parsed.payload.size))
        } catch (error: Exception) {
            synchronized(mapLock) { if (udp[key] === leg) udp.remove(key) }
            runCatching { leg.socket.close() }
            if (!closed) AppLog.w(TAG, "udp ${parsed.dstPort}: ${error.message}")
        }
    }

    private fun sendAck(leg: TcpLeg) {
        writeTun(replyTcp(leg, leg.serverSeq, leg.clientNext, 0x10, ByteArray(0)))
    }

    private fun sendRst(leg: TcpLeg) {
        runCatching { writeTun(replyTcp(leg, leg.serverSeq, leg.clientNext, 0x14, ByteArray(0))) }
    }

    private fun replyTcp(leg: TcpLeg, seq: Long, ack: Long, flags: Int, payload: ByteArray): ByteArray =
        IpPackets.tcpSegment(leg.server, leg.client, leg.v6, leg.serverPort, leg.clientPort, seq, ack, flags, payload)

    private fun dropTcp(key: String, socket: Socket) {
        synchronized(mapLock) { tcp.remove(key) }
        runCatching { socket.close() }
    }

    private fun key(parsed: IpDatagram): String {
        val src = parsed.src.joinToString(".") { (it.toInt() and 0xff).toString() }
        val dst = parsed.dst.joinToString(".") { (it.toInt() and 0xff).toString() }
        return "${parsed.version}|${parsed.proto}|$src|${parsed.srcPort}|$dst|${parsed.dstPort}"
    }

    private fun address(raw: ByteArray): InetAddress = InetAddress.getByAddress(raw)

    private class TcpLeg(
        val socket: Socket,
        val client: ByteArray,
        val clientPort: Int,
        val server: ByteArray,
        val serverPort: Int,
        val v6: Boolean,
        var clientNext: Long,
    ) {
        var serverSeq: Long = 0
        var up: Boolean = false
        var synAck: ByteArray? = null
        val early = ArrayDeque<ByteArray>()
    }

    private class UdpLeg(
        val socket: DatagramSocket,
        val client: ByteArray,
        val clientPort: Int,
        val server: ByteArray,
        val serverPort: Int,
        val v6: Boolean,
    )

    companion object {
        private const val TAG = "MagnonPcap"
        private const val MAX_FLOWS = 64
    }
}

package com.aksa.magnon

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One parsed IPv4 or IPv6 TCP/UDP packet. Addresses are in network order. */
internal data class IpDatagram(
    val version: Int,
    val proto: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val payload: ByteArray,
)

/**
 * Packet build and a little-endian raw-IP PCAP writer.
 * Link type 101 is the same DLT Wireshark uses for VPN captures.
 */
internal object IpPackets {
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

    fun parse(packet: ByteArray): IpDatagram? {
        if (packet.size < 20) return null
        return when ((packet[0].toInt() ushr 4) and 0xf) {
            4 -> parse4(packet)
            6 -> parse6(packet)
            else -> null
        }
    }

    fun tcpSegment(
        srcIp: ByteArray,
        dstIp: ByteArray,
        v6: Boolean,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
    ): ByteArray {
        val tcp = ByteArray(20 + payload.size)
        put16(tcp, 0, srcPort)
        put16(tcp, 2, dstPort)
        put32(tcp, 4, seq)
        put32(tcp, 8, ack)
        tcp[12] = (5 shl 4).toByte()
        tcp[13] = flags.toByte()
        put16(tcp, 14, 65535)
        if (payload.isNotEmpty()) payload.copyInto(tcp, 20)
        put16(tcp, 16, transportSum(srcIp, dstIp, 6, tcp, v6))
        return wrap(srcIp, dstIp, 6, tcp, v6)
    }

    fun udpSegment(
        srcIp: ByteArray,
        dstIp: ByteArray,
        v6: Boolean,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udp = ByteArray(8 + payload.size)
        put16(udp, 0, srcPort)
        put16(udp, 2, dstPort)
        put16(udp, 4, udp.size)
        if (payload.isNotEmpty()) payload.copyInto(udp, 8)
        put16(udp, 6, transportSum(srcIp, dstIp, 17, udp, v6))
        return wrap(srcIp, dstIp, 17, udp, v6)
    }

    /** One's complement sum of a header that already contains its checksum. Valid headers fold to 0. */
    fun foldedSum(data: ByteArray, offset: Int, length: Int): Int = finish(addBytes(data, offset, length, 0))

    private fun parse4(packet: ByteArray): IpDatagram? {
        val ihl = (packet[0].toInt() and 0xf) * 4
        if (ihl < 20 || packet.size < ihl) return null
        val total = minOf(u16(packet, 2), packet.size)
        if (total < ihl) return null
        val frag = u16(packet, 6) and 0x1fff
        if (frag != 0) return null
        val proto = packet[9].toInt() and 0xff
        if (proto != 6 && proto != 17) return null
        return l4(
            version = 4,
            proto = proto,
            src = packet.copyOfRange(12, 16),
            dst = packet.copyOfRange(16, 20),
            header = ihl,
            end = total,
            packet = packet,
        )
    }

    private fun parse6(packet: ByteArray): IpDatagram? {
        if (packet.size < 40) return null
        var next = packet[6].toInt() and 0xff
        var off = 40
        var guard = 0
        while (guard++ < 8 && next != 6 && next != 17) {
            if (next == 44 || off + 2 > packet.size) return null
            if (next != 0 && next != 43 && next != 60) return null
            val len = (packet[off + 1].toInt() and 0xff) * 8 + 8
            next = packet[off].toInt() and 0xff
            off += len
        }
        if (next != 6 && next != 17) return null
        val declared = 40 + u16(packet, 4)
        return l4(
            version = 6,
            proto = next,
            src = packet.copyOfRange(8, 24),
            dst = packet.copyOfRange(24, 40),
            header = off,
            end = minOf(packet.size, declared),
            packet = packet,
        )
    }

    private fun l4(
        version: Int,
        proto: Int,
        src: ByteArray,
        dst: ByteArray,
        header: Int,
        end: Int,
        packet: ByteArray,
    ): IpDatagram? {
        if (end < header + 8 || header < 0) return null
        val srcPort = u16(packet, header)
        val dstPort = u16(packet, header + 2)
        if (proto == 17) {
            val len = minOf(u16(packet, header + 4), end - header)
            if (len < 8) return null
            return IpDatagram(version, proto, src, dst, srcPort, dstPort, 0, 0, 0, packet.copyOfRange(header + 8, header + len))
        }
        if (end < header + 20) return null
        val dataOff = ((packet[header + 12].toInt() ushr 4) and 0xf) * 4
        if (dataOff < 20 || header + dataOff > end) return null
        return IpDatagram(
            version,
            proto,
            src,
            dst,
            srcPort,
            dstPort,
            u32(packet, header + 4),
            u32(packet, header + 8),
            packet[header + 13].toInt() and 0xff,
            packet.copyOfRange(header + dataOff, end),
        )
    }

    private fun wrap(src: ByteArray, dst: ByteArray, proto: Int, l4: ByteArray, v6: Boolean): ByteArray {
        if (!v6) {
            val out = ByteArray(20 + l4.size)
            out[0] = 0x45
            put16(out, 2, out.size)
            put16(out, 4, nextId.getAndIncrement() and 0xffff)
            out[6] = 0x40
            out[8] = 64
            out[9] = proto.toByte()
            src.copyInto(out, 12)
            dst.copyInto(out, 16)
            put16(out, 10, foldedSum(out, 0, 20).let { if (it == 0) 0xffff else it })
            l4.copyInto(out, 20)
            return out
        }
        val out = ByteArray(40 + l4.size)
        out[0] = 0x60
        put16(out, 4, l4.size)
        out[6] = proto.toByte()
        out[7] = 64
        src.copyInto(out, 8)
        dst.copyInto(out, 24)
        l4.copyInto(out, 40)
        return out
    }

    private fun transportSum(src: ByteArray, dst: ByteArray, proto: Int, segment: ByteArray, v6: Boolean): Int {
        var sum = addBytes(src, 0, src.size, 0)
        sum = addBytes(dst, 0, dst.size, sum)
        if (v6) {
            sum += (segment.size ushr 16) and 0xffff
            sum += segment.size and 0xffff
        } else {
            sum += segment.size and 0xffff
        }
        sum += proto
        sum = addBytes(segment, 0, segment.size, sum)
        val folded = finish(sum)
        return if (folded == 0) 0xffff else folded
    }

    private fun addBytes(data: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial
        var i = offset
        var left = length
        while (left > 1) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
            left -= 2
        }
        if (left == 1) sum += (data[i].toInt() and 0xff) shl 8
        return sum
    }

    private fun finish(sum: Int): Int {
        var value = sum
        while (value ushr 16 != 0) value = (value and 0xffff) + (value ushr 16)
        return value.inv() and 0xffff
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or
            ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or
            (b[o + 3].toLong() and 0xff)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        val n = v and 0xffffffffL
        b[o] = (n ushr 24).toByte()
        b[o + 1] = (n ushr 16).toByte()
        b[o + 2] = (n ushr 8).toByte()
        b[o + 3] = n.toByte()
    }
}

internal class PcapFile(file: File, private val maxBytes: Long = MAX_BYTES) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var size = 24L
    var packets = 0L
        private set
    var capped = false
        private set

    @Synchronized
    fun sizeBytes(): Long = size

    init {
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0xa1b2c3d4.toInt())
        head.putShort(2)
        head.putShort(4)
        head.putInt(0)
        head.putInt(0)
        head.putInt(65535)
        head.putInt(101)
        out.write(head.array())
    }

    @Synchronized
    fun write(packet: ByteArray): Boolean {
        if (capped) return false
        val next = size + 16L + packet.size
        if (next > maxBytes) {
            capped = true
            return false
        }
        val rec = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        val now = System.currentTimeMillis()
        rec.putInt((now / 1000L).toInt())
        rec.putInt(((now % 1000L) * 1000L).toInt())
        rec.putInt(packet.size)
        rec.putInt(packet.size)
        out.write(rec.array())
        out.write(packet)
        size = next
        packets += 1
        if (packets % 32L == 0L) out.flush()
        return true
    }

    @Synchronized
    override fun close() {
        out.flush()
        out.close()
    }

    companion object {
        const val MAX_BYTES = 50L * 1024L * 1024L
    }
}

package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapIoTest {
    @Test
    fun ipv4TcpChecksumFoldsToZeroAndParses() {
        val packet = IpPackets.tcpSegment(
            srcIp = byteArrayOf(10, 215.toByte(), 0, 2),
            dstIp = byteArrayOf(1, 1, 1, 1),
            v6 = false,
            srcPort = 443,
            dstPort = 50000,
            seq = 10,
            ack = 2,
            flags = 0x18,
            payload = "ok".encodeToByteArray(),
        )
        assertEquals(0, IpPackets.foldedSum(packet, 0, 20))
        val parsed = IpPackets.parse(packet)
        assertEquals(4, parsed?.version)
        assertEquals(6, parsed?.proto)
        assertEquals(443, parsed?.srcPort)
        assertEquals(50000, parsed?.dstPort)
        assertEquals(10L, parsed?.seq)
        assertEquals(2L, parsed?.ack)
        assertEquals(0x18, parsed?.flags)
        assertEquals("ok", parsed?.payload?.decodeToString())
    }

    @Test
    fun pcapIsLittleEndianRawIpAndStopsAtTheCap() {
        val packet = IpPackets.udpSegment(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(1, 1, 1, 1),
            v6 = false,
            srcPort = 12345,
            dstPort = 53,
            payload = byteArrayOf(1, 2, 3, 4),
        )
        val file = File.createTempFile("magnon-pcap", ".pcap")
        try {
            val cap = 24L + 16L + packet.size
            PcapFile(file, cap).use { pcap ->
                assertTrue(pcap.write(packet))
                assertFalse(pcap.write(packet))
                assertTrue(pcap.capped)
                assertEquals(1L, pcap.packets)
            }
            val bytes = file.readBytes()
            assertEquals(0xd4.toByte(), bytes[0])
            assertEquals(0xc3.toByte(), bytes[1])
            assertEquals(0xb2.toByte(), bytes[2])
            assertEquals(0xa1.toByte(), bytes[3])
            val link = ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(101, link)
        } finally {
            file.delete()
        }
    }
}

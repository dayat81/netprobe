package com.telcoagent.udpclient

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UDP probe binary protocol — UTC epoch nanoseconds (requires NTP sync for one-way delays).
 *
 * v1 Request (15 B): magic + version + seq(u32) + t1(i64)
 * v1 Reply   (31 B): magic + version + seq + t1 + t2 + t3
 * v2 Request (19 B): magic + version + round_id(u16) + seq(u16) + t1(i64)
 * v2 Reply   (43 B): v1 fields + server_rx(u16) + server_tx(u16)
 * Summary req (7 B): US + version + round_id(u16)
 * Summary rep (11 B): US + version + round_id + server_rx + server_tx
 */
object UdpProbeProtocol {
    const val SYNC_ROUND_ID = 0
    const val VERSION: Byte = 1
    const val VERSION_V2: Byte = 2
    const val SUMMARY_VERSION: Byte = 1
    const val REQUEST_SIZE = 15
    const val REPLY_SIZE = 31
    const val REQUEST_V2_SIZE = 15
    const val REPLY_V2_SIZE = 35
    const val SUMMARY_REQUEST_SIZE = 5
    const val SUMMARY_REPLY_SIZE = 9

    private val MAGIC = byteArrayOf(0x55, 0x44) // "UD"
    private val SUMMARY_MAGIC = byteArrayOf(0x55, 0x53) // "US"

    data class ProbeRequest(val seq: Int, val t1: Long)

    data class ProbeReply(val seq: Int, val t1: Long, val t2: Long, val t3: Long)

    data class ProbeRequestV2(val roundId: Int, val seq: Int, val t1: Long)

    data class ProbeReplyV2(
        val roundId: Int,
        val seq: Int,
        val t1: Long,
        val t2: Long,
        val t3: Long,
        val serverRx: Int,
        val serverTx: Int,
    )

    data class SummaryReply(val roundId: Int, val serverRx: Int, val serverTx: Int)

    data class ProbeLatency(
        val seq: Int,
        val uplinkMs: Double,
        val downlinkMs: Double,
        val rttMs: Double,
    )

    fun isProbeRequest(data: ByteArray, length: Int = data.size): Boolean {
        return length >= REQUEST_SIZE && data[0] == MAGIC[0] && data[1] == MAGIC[1]
    }

    fun isSummaryRequest(data: ByteArray, length: Int = data.size): Boolean {
        return length >= SUMMARY_REQUEST_SIZE && data[0] == SUMMARY_MAGIC[0] && data[1] == SUMMARY_MAGIC[1]
    }

    fun packRequest(seq: Int, t1: Long): ByteArray {
        return ByteBuffer.allocate(REQUEST_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION)
            .putInt(seq)
            .putLong(t1)
            .array()
    }

    fun unpackRequest(data: ByteArray, length: Int = data.size): ProbeRequest {
        require(length >= REQUEST_SIZE) { "request too short: $length" }
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = byteArrayOf(buf.get(), buf.get())
        require(magic.contentEquals(MAGIC)) { "bad magic" }
        val version = buf.get()
        require(version == VERSION) { "unsupported version: $version" }
        val seq = buf.int
        val t1 = buf.long
        return ProbeRequest(seq, t1)
    }

    fun packRequestV2(roundId: Int, seq: Int, t1: Long): ByteArray {
        return ByteBuffer.allocate(REQUEST_V2_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION_V2)
            .putShort(roundId.toShort())
            .putShort(seq.toShort())
            .putLong(t1)
            .array()
    }

    fun unpackRequestV2(data: ByteArray, length: Int = data.size): ProbeRequestV2 {
        require(length >= REQUEST_V2_SIZE) { "request too short: $length" }
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = byteArrayOf(buf.get(), buf.get())
        require(magic.contentEquals(MAGIC)) { "bad magic" }
        val version = buf.get()
        require(version == VERSION_V2) { "unsupported version: $version" }
        val roundId = buf.short.toInt() and 0xFFFF
        val seq = buf.short.toInt() and 0xFFFF
        val t1 = buf.long
        return ProbeRequestV2(roundId, seq, t1)
    }

    fun packReply(seq: Int, t1: Long, t2: Long, t3: Long): ByteArray {
        return ByteBuffer.allocate(REPLY_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION)
            .putInt(seq)
            .putLong(t1)
            .putLong(t2)
            .putLong(t3)
            .array()
    }

    fun unpackReply(data: ByteArray, length: Int = data.size): ProbeReply {
        require(length >= REPLY_SIZE) { "reply too short: $length" }
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = byteArrayOf(buf.get(), buf.get())
        require(magic.contentEquals(MAGIC)) { "bad magic" }
        val version = buf.get()
        require(version == VERSION) { "unsupported version: $version" }
        val seq = buf.int
        val t1 = buf.long
        val t2 = buf.long
        val t3 = buf.long
        return ProbeReply(seq, t1, t2, t3)
    }

    fun unpackReplyV2(data: ByteArray, length: Int = data.size): ProbeReplyV2 {
        require(length >= REPLY_V2_SIZE) { "reply too short: $length" }
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = byteArrayOf(buf.get(), buf.get())
        require(magic.contentEquals(MAGIC)) { "bad magic" }
        val version = buf.get()
        require(version == VERSION_V2) { "unsupported version: $version" }
        val roundId = buf.short.toInt() and 0xFFFF
        val seq = buf.short.toInt() and 0xFFFF
        val t1 = buf.long
        val t2 = buf.long
        val t3 = buf.long
        val serverRx = buf.short.toInt() and 0xFFFF
        val serverTx = buf.short.toInt() and 0xFFFF
        return ProbeReplyV2(roundId, seq, t1, t2, t3, serverRx, serverTx)
    }

    fun packSummaryRequest(roundId: Int): ByteArray {
        return ByteBuffer.allocate(SUMMARY_REQUEST_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SUMMARY_MAGIC)
            .put(SUMMARY_VERSION)
            .putShort(roundId.toShort())
            .array()
    }

    fun unpackSummaryReply(data: ByteArray, length: Int = data.size): SummaryReply {
        require(length >= SUMMARY_REPLY_SIZE) { "summary reply too short: $length" }
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = byteArrayOf(buf.get(), buf.get())
        require(magic.contentEquals(SUMMARY_MAGIC)) { "bad summary magic" }
        val version = buf.get()
        require(version == SUMMARY_VERSION) { "unsupported summary version: $version" }
        val roundId = buf.short.toInt() and 0xFFFF
        val serverRx = buf.short.toInt() and 0xFFFF
        val serverTx = buf.short.toInt() and 0xFFFF
        return SummaryReply(roundId, serverRx, serverTx)
    }

    fun computeLatency(reply: ProbeReply, t4: Long): ProbeLatency {
        val uplinkMs = (reply.t2 - reply.t1) / 1_000_000.0
        val downlinkMs = (t4 - reply.t3) / 1_000_000.0
        return ProbeLatency(
            seq = reply.seq,
            uplinkMs = uplinkMs,
            downlinkMs = downlinkMs,
            rttMs = uplinkMs + downlinkMs,
        )
    }

    fun computeLatencyV2(reply: ProbeReplyV2, t4: Long): ProbeLatency {
        return computeLatency(ProbeReply(reply.seq, reply.t1, reply.t2, reply.t3), t4)
    }

    fun uplinkLossPct(sent: Int, serverRx: Int): Double {
        if (sent <= 0) return 0.0
        return maxOf(0.0, (sent - serverRx).toDouble() / sent * 100.0)
    }

    fun downlinkLossPct(serverTx: Int, replyRx: Int): Double {
        if (serverTx <= 0) return 0.0
        return maxOf(0.0, (serverTx - replyRx).toDouble() / serverTx * 100.0)
    }

    fun isValidLatency(lat: ProbeLatency, toleranceMs: Double = 0.0): Boolean {
        return lat.uplinkMs >= -toleranceMs && lat.downlinkMs >= -toleranceMs
    }

    fun clockOffsetMs(uplinkMs: Double, downlinkMs: Double): Double {
        return (uplinkMs - downlinkMs) / 2.0
    }

    fun estimateClockOffsetMs(samples: List<ProbeLatency>): Double? {
        return estimateOffsetFromSamples(samples)
    }

    fun estimateOffsetFromSamples(samples: List<ProbeLatency>): Double? {
        if (samples.isEmpty()) return null
        val offsets = samples.map { clockOffsetMs(it.uplinkMs, it.downlinkMs) }.sorted()
        val mid = offsets.size / 2
        return if (offsets.size % 2 == 0) {
            (offsets[mid - 1] + offsets[mid]) / 2.0
        } else {
            offsets[mid]
        }
    }

    fun correctLatency(lat: ProbeLatency, offsetMs: Double): ProbeLatency {
        val uplinkMs = lat.uplinkMs - offsetMs
        val downlinkMs = lat.downlinkMs + offsetMs
        return ProbeLatency(
            seq = lat.seq,
            uplinkMs = uplinkMs,
            downlinkMs = downlinkMs,
            rttMs = uplinkMs + downlinkMs,
        )
    }

    fun correctedToleranceMs(rttMs: Double, minimumMs: Double = 1.0): Double {
        return maxOf(minimumMs, 0.02 * rttMs)
    }

    data class FinalizedRound(
        val offsetMs: Double?,
        val accepted: List<ProbeLatency>,
        val skewRejected: Int,
    )

    fun finalizeRoundLatencies(
        samples: List<ProbeLatency>,
        minimumToleranceMs: Double = 1.0,
        applyOffsetCorrection: Boolean = true,
        fixedOffsetMs: Double? = null,
    ): FinalizedRound {
        if (samples.isEmpty()) return FinalizedRound(fixedOffsetMs, emptyList(), 0)
        val offset = fixedOffsetMs ?: estimateOffsetFromSamples(samples)
        if (!applyOffsetCorrection) {
            val accepted = samples.filter { it.rttMs > 0.0 && it.rttMs < 60_000.0 }
            return FinalizedRound(offset, accepted, samples.size - accepted.size)
        }
        val accepted = mutableListOf<ProbeLatency>()
        var rejected = 0
        for (lat in samples) {
            val corrected = correctLatency(lat, offset ?: 0.0)
            val tol = correctedToleranceMs(corrected.rttMs, minimumToleranceMs)
            if (isValidLatency(corrected, tol)) {
                accepted.add(corrected)
            } else {
                rejected += 1
            }
        }
        return FinalizedRound(offset, accepted, rejected)
    }

    /** UTC epoch nanoseconds from wall clock. */
    fun nowUtcNanos(): Long {
        val instant = java.time.Instant.now()
        return instant.epochSecond * 1_000_000_000L + instant.nano
    }
}

package com.telcoagent.udpclient

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class ProbeConfig(
    val host: String = "netprobe.xyz",
    val port: Int = 8765,
    val rounds: Int = 5,
    val packets: Int = 15,
    val pauseMs: Long = 500,
    val recvTimeoutMs: Int = 3000,
    val packetDelayMs: Long = 0,
    val useBinaryProtocol: Boolean = true,
    val useProtocolV2: Boolean = true,
    val applyOffsetCorrection: Boolean = true,
    val syncPackets: Int = 3,
    val fixedOffsetMs: Double? = null,
    val summaryTimeoutMs: Int = 500,
)

data class ProbeProgress(
    val roundNo: Int,
    val packetNo: Int,
    val totalRounds: Int,
    val totalPackets: Int,
)

private data class PendingProbe(
    val seq: Int,
    val raw: UdpProbeProtocol.ProbeLatency,
    val localRttMs: Double,
)

class UdpProbeClient {
    suspend fun runProbe(
        config: ProbeConfig = ProbeConfig(),
        onLog: suspend (String) -> Unit,
        onProgress: suspend (ProbeProgress) -> Unit = {},
        onRoundDone: suspend (RoundMetrics) -> Unit,
    ): OverallMetrics = withContext(Dispatchers.IO) {
        val targetAddress = InetAddress.getByName(config.host)
        val roundMetrics = mutableListOf<RoundMetrics>()

        onLog("Connecting to ${config.host}:${config.port}")

        var sessionOffset = config.fixedOffsetMs
        if (sessionOffset == null &&
            config.useBinaryProtocol &&
            config.applyOffsetCorrection &&
            config.syncPackets > 0
        ) {
            val syncSocket = DatagramSocket()
            try {
                syncSocket.soTimeout = config.recvTimeoutMs
                onLog("Clock sync…")
                sessionOffset = measureClockOffset(syncSocket, targetAddress, config, onLog)
            } finally {
                syncSocket.close()
            }
        }

        for (roundNo in 1..config.rounds) {
            coroutineContext.ensureActive()
            val metrics = if (config.useBinaryProtocol) {
                sendRoundBinary(
                    config = config,
                    roundNo = roundNo,
                    targetAddress = targetAddress,
                    fixedOffsetMs = sessionOffset,
                    onLog = onLog,
                    onProgress = onProgress,
                )
            } else {
                sendRoundText(config, roundNo, targetAddress, onLog, onProgress)
            }
            roundMetrics.add(metrics)
            onRoundDone(metrics)
            if (roundNo < config.rounds && config.pauseMs > 0) {
                delay(config.pauseMs)
            }
        }

        OverallMetrics(roundMetrics)
    }

    private suspend fun sendRoundBinary(
        config: ProbeConfig,
        roundNo: Int,
        targetAddress: InetAddress,
        fixedOffsetMs: Double? = null,
        onLog: suspend (String) -> Unit,
        onProgress: suspend (ProbeProgress) -> Unit,
    ): RoundMetrics {
        val socket = DatagramSocket()
        val metrics = RoundMetrics(roundNo = roundNo)
        val receiveBuffer = ByteArray(1024)
        val pending = mutableListOf<PendingProbe>()
        val roundId = roundNo and 0xFFFF
        var useV2 = config.useProtocolV2

        try {
            socket.soTimeout = config.recvTimeoutMs
            onLog("Round $roundNo started (binary${if (useV2) " v2" else ""}${offsetModeLabel(config)})")

            for (seq in 1..config.packets) {
                coroutineContext.ensureActive()
                onProgress(
                    ProbeProgress(
                        roundNo = roundNo,
                        packetNo = seq,
                        totalRounds = config.rounds,
                        totalPackets = config.packets,
                    ),
                )

                val t1 = UdpProbeProtocol.nowUtcNanos()
                val payload = if (useV2) {
                    UdpProbeProtocol.packRequestV2(roundId, seq, t1)
                } else {
                    UdpProbeProtocol.packRequest(seq, t1)
                }
                val sendPacket = DatagramPacket(payload, payload.size, targetAddress, config.port)
                val sendNs = System.nanoTime()
                socket.send(sendPacket)
                metrics.sent += 1

                try {
                    val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(replyPacket)
                    val localRttMs = (System.nanoTime() - sendNs) / 1_000_000.0
                    val t4 = UdpProbeProtocol.nowUtcNanos()
                    val length = replyPacket.length

                    if (useV2 && length >= UdpProbeProtocol.REPLY_V2_SIZE && receiveBuffer[2] == UdpProbeProtocol.VERSION_V2) {
                        val reply = UdpProbeProtocol.unpackReplyV2(receiveBuffer, length)
                        if (reply.roundId != roundId || reply.seq != seq || reply.t1 != t1) {
                            onLog("  ! pkt $seq  reply mismatch")
                            retryBasicRtt(socket, targetAddress, config, roundNo, seq, receiveBuffer, metrics, onLog)
                            continue
                        }
                        metrics.noteServerCounts(reply.serverRx, reply.serverTx)
                        metrics.recordReplyMatch()
                        val raw = UdpProbeProtocol.computeLatency(
                            UdpProbeProtocol.ProbeReply(reply.seq, reply.t1, reply.t2, reply.t3),
                            t4,
                        )
                        pending.add(PendingProbe(seq, raw, localRttMs))
                        logPacketLatency(onLog, seq, raw, config.applyOffsetCorrection)
                    } else if (!useV2 || length >= UdpProbeProtocol.REPLY_SIZE) {
                        if (useV2 && length >= UdpProbeProtocol.REPLY_SIZE && receiveBuffer[2] == UdpProbeProtocol.VERSION) {
                            useV2 = false
                            onLog("  · server replied v1 — falling back for this session")
                        }
                        val reply = UdpProbeProtocol.unpackReply(receiveBuffer, length)
                        if (reply.seq != seq || reply.t1 != t1) {
                            onLog("  ! pkt $seq  reply mismatch")
                            retryBasicRtt(socket, targetAddress, config, roundNo, seq, receiveBuffer, metrics, onLog)
                            continue
                        }
                        metrics.recordReplyMatch()
                        val raw = UdpProbeProtocol.computeLatency(reply, t4)
                        pending.add(PendingProbe(seq, raw, localRttMs))
                        logPacketLatency(onLog, seq, raw, config.applyOffsetCorrection)
                    } else {
                        onLog("  ! pkt $seq  short reply ($length bytes)")
                        retryBasicRtt(socket, targetAddress, config, roundNo, seq, receiveBuffer, metrics, onLog)
                    }
                } catch (_: SocketTimeoutException) {
                    onLog("  ✗ pkt $seq  timeout")
                    retryBasicRtt(socket, targetAddress, config, roundNo, seq, receiveBuffer, metrics, onLog)
                } catch (e: IllegalArgumentException) {
                    onLog("  ✗ pkt $seq  bad reply: ${e.message}")
                    retryBasicRtt(socket, targetAddress, config, roundNo, seq, receiveBuffer, metrics, onLog)
                }

                if (config.packetDelayMs > 0 && seq < config.packets) {
                    delay(config.packetDelayMs)
                }
            }

            finalizeRoundMetrics(metrics, pending, config, fixedOffsetMs, onLog)

            if (useV2) {
                requestRoundSummary(socket, targetAddress, config, roundId, metrics, onLog)
            }

            val upLoss = metrics.uplinkLossPct
            val downLoss = metrics.downlinkLossPct
            onLog(
                "Round $roundNo done — rtt loss ${"%.0f".format(metrics.lossPct)}% " +
                    "up loss ${upLoss?.let { "%.0f".format(it) } ?: "—"}% " +
                    "down loss ${downLoss?.let { "%.0f".format(it) } ?: "—"}% " +
                    "offset ${fmtMs(metrics.clockOffsetMs)} " +
                    "up ${fmtMs(metrics.avgUplinkMs)} down ${fmtMs(metrics.avgDownlinkMs)}",
            )
        } finally {
            socket.close()
        }

        return metrics
    }

    private suspend fun retryBasicRtt(
        socket: DatagramSocket,
        targetAddress: InetAddress,
        config: ProbeConfig,
        roundNo: Int,
        seq: Int,
        receiveBuffer: ByteArray,
        metrics: RoundMetrics,
        onLog: suspend (String) -> Unit,
    ) {
        val rttMs = sendBasicProbe(socket, targetAddress, config.port, roundNo, seq, receiveBuffer)
        if (rttMs != null) {
            metrics.recordReply(rttMs)
            onLog("  ✓ pkt $seq  basic ${"%.0f".format(rttMs)} ms")
        }
    }

    private fun sendBasicProbe(
        socket: DatagramSocket,
        targetAddress: InetAddress,
        port: Int,
        roundNo: Int,
        seq: Int,
        receiveBuffer: ByteArray,
    ): Double? {
        val payload = "round-$roundNo-pkt-$seq".toByteArray()
        val sendPacket = DatagramPacket(payload, payload.size, targetAddress, port)
        val startNs = System.nanoTime()
        socket.send(sendPacket)
        return try {
            val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(replyPacket)
            (System.nanoTime() - startNs) / 1_000_000.0
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    suspend fun measureClockOffset(
        socket: DatagramSocket,
        targetAddress: InetAddress,
        config: ProbeConfig,
        onLog: suspend (String) -> Unit = {},
    ): Double? {
        if (config.syncPackets <= 0) return null
        val samples = mutableListOf<UdpProbeProtocol.ProbeLatency>()
        val receiveBuffer = ByteArray(1024)
        val roundId = UdpProbeProtocol.SYNC_ROUND_ID

        for (seq in 1..config.syncPackets) {
            coroutineContext.ensureActive()
            val t1 = UdpProbeProtocol.nowUtcNanos()
            val payload = UdpProbeProtocol.packRequestV2(roundId, seq, t1)
            socket.send(DatagramPacket(payload, payload.size, targetAddress, config.port))
            try {
                val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(replyPacket)
                val t4 = UdpProbeProtocol.nowUtcNanos()
                val length = replyPacket.length
                if (length >= UdpProbeProtocol.REPLY_V2_SIZE && receiveBuffer[2] == UdpProbeProtocol.VERSION_V2) {
                    val reply = UdpProbeProtocol.unpackReplyV2(receiveBuffer, length)
                    if (reply.roundId == roundId && reply.seq == seq && reply.t1 == t1) {
                        samples.add(
                            UdpProbeProtocol.computeLatency(
                                UdpProbeProtocol.ProbeReply(reply.seq, reply.t1, reply.t2, reply.t3),
                                t4,
                            ),
                        )
                    }
                } else if (length >= UdpProbeProtocol.REPLY_SIZE && receiveBuffer[2] == UdpProbeProtocol.VERSION) {
                    val reply = UdpProbeProtocol.unpackReply(receiveBuffer, length)
                    if (reply.seq == seq && reply.t1 == t1) {
                        samples.add(UdpProbeProtocol.computeLatency(reply, t4))
                    }
                }
            } catch (_: SocketTimeoutException) {
                // continue with fewer samples
            } catch (_: IllegalArgumentException) {
                // bad reply, skip sample
            }
            if (config.packetDelayMs > 0 && seq < config.syncPackets) {
                delay(config.packetDelayMs)
            }
        }

        val offset = UdpProbeProtocol.estimateOffsetFromSamples(samples)
        if (offset != null) {
            onLog(
                "Clock sync: offset ${"%.1f".format(offset)} ms " +
                    "(${samples.size}/${config.syncPackets} samples)",
            )
        } else {
            onLog("Clock sync: no samples — per-round offset estimate")
        }
        return offset
    }

    private suspend fun finalizeRoundMetrics(
        metrics: RoundMetrics,
        pending: List<PendingProbe>,
        config: ProbeConfig,
        fixedOffsetMs: Double?,
        onLog: suspend (String) -> Unit,
    ) {
        if (pending.isEmpty()) return
        val rawSamples = pending.map { it.raw }
        val result = UdpProbeProtocol.finalizeRoundLatencies(
            rawSamples,
            applyOffsetCorrection = config.applyOffsetCorrection,
            fixedOffsetMs = fixedOffsetMs,
        )
        metrics.clockOffsetMs = result.offsetMs
        metrics.skewRejected = result.skewRejected
        val acceptedSeqs = result.accepted.map { it.seq }.toSet()
        for (accepted in result.accepted) {
            metrics.recordProbe(accepted)
        }
        for (probe in pending) {
            if (probe.seq !in acceptedSeqs) {
                metrics.recordReply(probe.localRttMs)
                onLog(
                    "  · pkt ${probe.seq} basic ${"%.0f".format(probe.localRttMs)} ms " +
                        "(timestamp invalid — local RTT)",
                )
            }
        }
        if (!config.applyOffsetCorrection) {
            onLog(
                "  · raw timestamps — offset est. ${fmtMs(result.offsetMs)} (correction off)",
            )
        } else if (result.skewRejected > 0) {
            onLog(
                "  ! ${result.skewRejected} pkt(s) skew-rejected — used basic RTT fallback " +
                    "(offset ${fmtMs(result.offsetMs)})",
            )
        }
    }

    private suspend fun logPacketLatency(
        onLog: suspend (String) -> Unit,
        seq: Int,
        raw: UdpProbeProtocol.ProbeLatency,
        applyOffsetCorrection: Boolean,
    ) {
        if (applyOffsetCorrection) {
            onLog("  ✓ pkt $seq  raw rtt ${"%.0f".format(raw.rttMs)} ms")
        } else {
            onLog(
                "  ✓ pkt $seq  raw up ${"%.0f".format(raw.uplinkMs)} ms  " +
                    "down ${"%.0f".format(raw.downlinkMs)} ms  " +
                    "rtt ${"%.0f".format(raw.rttMs)} ms",
            )
        }
    }

    private fun offsetModeLabel(config: ProbeConfig): String {
        return if (config.applyOffsetCorrection) "" else " · raw timestamps"
    }

    private suspend fun requestRoundSummary(
        socket: DatagramSocket,
        targetAddress: InetAddress,
        config: ProbeConfig,
        roundId: Int,
        metrics: RoundMetrics,
        onLog: suspend (String) -> Unit,
    ) {
        val receiveBuffer = ByteArray(1024)
        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = config.summaryTimeoutMs
            val payload = UdpProbeProtocol.packSummaryRequest(roundId)
            socket.send(DatagramPacket(payload, payload.size, targetAddress, config.port))
            val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(replyPacket)
            val summary = UdpProbeProtocol.unpackSummaryReply(receiveBuffer, replyPacket.length)
            if (summary.roundId == roundId) {
                metrics.applySummary(summary.serverRx, summary.serverTx)
                onLog("  · summary rx=${summary.serverRx} tx=${summary.serverTx}")
            }
        } catch (_: SocketTimeoutException) {
            onLog("  · summary timeout (using reply counters)")
        } catch (e: Exception) {
            onLog("  · summary failed: ${e.message}")
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    private suspend fun sendRoundText(
        config: ProbeConfig,
        roundNo: Int,
        targetAddress: InetAddress,
        onLog: suspend (String) -> Unit,
        onProgress: suspend (ProbeProgress) -> Unit,
    ): RoundMetrics {
        val socket = DatagramSocket()
        val metrics = RoundMetrics(roundNo = roundNo)
        val receiveBuffer = ByteArray(1024)

        try {
            socket.soTimeout = config.recvTimeoutMs
            onLog("Round $roundNo started (text)")

            for (seq in 1..config.packets) {
                coroutineContext.ensureActive()
                onProgress(
                    ProbeProgress(
                        roundNo = roundNo,
                        packetNo = seq,
                        totalRounds = config.rounds,
                        totalPackets = config.packets,
                    ),
                )

                val payload = "round-$roundNo-pkt-$seq".toByteArray()
                val sendPacket = DatagramPacket(payload, payload.size, targetAddress, config.port)
                val startNs = System.nanoTime()
                socket.send(sendPacket)
                metrics.sent += 1

                try {
                    val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(replyPacket)
                    val rttMs = (System.nanoTime() - startNs) / 1_000_000.0
                    metrics.recordReply(rttMs)
                    onLog("  ✓ pkt $seq  ${"%.0f".format(rttMs)} ms")
                } catch (_: SocketTimeoutException) {
                    onLog("  ✗ pkt $seq  timeout")
                }

                if (config.packetDelayMs > 0 && seq < config.packets) {
                    delay(config.packetDelayMs)
                }
            }

            onLog(
                "Round $roundNo done — loss ${"%.0f".format(metrics.lossPct)}%, " +
                    "avg ${fmtMs(metrics.avgLatencyMs)}",
            )
        } finally {
            socket.close()
        }

        return metrics
    }

    companion object {
        val LOG_START = ProbeConfig(rounds = 1, packets = 10, pauseMs = 0)

        val LOG_CONTINUOUS = ProbeConfig(
            rounds = 1,
            packets = 5,
            pauseMs = 0,
            recvTimeoutMs = 2000,
        )

        fun continuousConfig(context: Context): ProbeConfig {
            return LOG_CONTINUOUS.copy(
                applyOffsetCorrection = ProbePreferences.isOffsetCorrectionEnabled(context),
            )
        }

        fun logStartConfig(context: Context): ProbeConfig {
            return LOG_START.copy(
                applyOffsetCorrection = ProbePreferences.isOffsetCorrectionEnabled(context),
            )
        }
    }
}

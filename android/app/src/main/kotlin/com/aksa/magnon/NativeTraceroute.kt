package com.aksa.magnon

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PingTools-style traceroute: exec packaged `libtraceroute.so` (Dmitry Butskoy /
 * traceroute.sourceforge.net build for Android) via ProcessBuilder — not JNI.
 *
 * Argv mirrors PingTools TraceUtils: `-4|-6 -n -m HOPS -N 16 -w SEC -q 1 HOST`
 */
object NativeTraceroute {
    private const val TAG = "MySignalGw"
    private const val BIN = "libtraceroute.so"

    data class Hop(val ttl: Int, val host: String?, val rttMs: Double?)

    /** First responding hop (skip `*`), used as cell upstream when hop 1 is silent. */
    fun firstRespondingHop(
        context: Context,
        target: String = "8.8.8.8",
        ipv6: Boolean = false,
        maxHops: Int = 8,
        timeoutSec: Long = 12,
    ): String? {
        val hops = run(
            context,
            target,
            ipv6 = ipv6,
            maxHops = maxHops,
            timeoutSec = timeoutSec,
        )
        return hops.firstOrNull { !it.host.isNullOrBlank() }?.host
    }

    /** IPv6 traceroute to a public dual-stack target (Google DNS). */
    fun firstRespondingHopV6(
        context: Context,
        target: String = "2001:4860:4860::8888",
        maxHops: Int = 8,
        timeoutSec: Long = 12,
    ): String? = firstRespondingHop(
        context,
        target = target,
        ipv6 = true,
        maxHops = maxHops,
        timeoutSec = timeoutSec,
    )

    fun run(
        context: Context,
        target: String,
        ipv6: Boolean = false,
        maxHops: Int = 30,
        probes: Int = 1,
        waitSec: Double = 1.0,
        timeoutSec: Long = 20,
    ): List<Hop> {
        val bin = resolveBinary(context) ?: run {
            AppLog.w(TAG, "traceroute binary missing")
            return emptyList()
        }
        val argv = mutableListOf(
            bin.absolutePath,
            if (ipv6) "-6" else "-4",
            "-n",
            "-m", maxHops.toString(),
            "-N", "16",
            "-w", waitSec.toString(),
            "-q", probes.toString(),
            target,
        )
        AppLog.i(TAG, "traceroute cmd=${argv.joinToString(" ")}")
        return try {
            val proc = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .directory(bin.parentFile)
                .start()
            val out = StringBuilder()
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { stream ->
                        val buf = CharArray(512)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            out.append(buf, 0, n)
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            try {
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        proc.destroyForcibly()
                        AppLog.i(TAG, "traceroute cancelled")
                        return emptyList()
                    }
                    if (proc.waitFor(200, TimeUnit.MILLISECONDS)) break
                    if (System.nanoTime() > deadline) {
                        proc.destroyForcibly()
                        AppLog.w(TAG, "traceroute timeout")
                        return emptyList()
                    }
                }
            } finally {
                reader.join(1_000)
            }
            val text = out.toString()
            AppLog.i(TAG, "traceroute out:\n${text.take(2000)}")
            parseHops(text)
        } catch (e: Exception) {
            AppLog.w(TAG, "traceroute failed: ${e.message}")
            emptyList()
        }
    }

    private fun resolveBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val fromNative = File(nativeDir, BIN)
        if (fromNative.isFile && fromNative.canExecute()) return fromNative
        // Fallback: copy from APK lib path variants
        val candidates = listOf(
            fromNative,
            File(context.filesDir, BIN),
            File(context.applicationInfo.sourceDir).parentFile?.let { File(it, "lib/arm64/$BIN") },
            File(context.applicationInfo.sourceDir).parentFile?.let { File(it, "lib/arm64-v8a/$BIN") },
        )
        return candidates.filterNotNull().firstOrNull { it.isFile }
            ?.also { f ->
                if (!f.canExecute()) runCatching { f.setExecutable(true) }
            }
    }

    /** Lines like ` 2  10.195.198.193  8.187 ms` or ` 1  *`. */
    internal fun parseHops(output: String): List<Hop> {
        val hops = mutableListOf<Hop>()
        for (line in output.lineSequence()) {
            val m = HOP_RE.matchEntire(line.trim()) ?: continue
            val ttl = m.groupValues[1].toIntOrNull() ?: continue
            val body = m.groupValues[2].trim()
            if (body == "*" || body.startsWith("*")) {
                hops.add(Hop(ttl, null, null))
                continue
            }
            val host = HOST_RE.find(body)?.groupValues?.getOrNull(1)
            val rtt = RTT_RE.find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            hops.add(Hop(ttl, host, rtt))
        }
        return hops
    }

    private val HOP_RE = Regex("""^(\d+)\s+(.+)$""")
    private val HOST_RE = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}|[0-9a-fA-F:]+)""")
    private val RTT_RE = Regex("""([\d.]+)\s*ms""")
}

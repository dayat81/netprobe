package com.aksa.magnon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Local VPN that records one selected app and forwards its TCP and UDP. */
class PcapVpnService : VpnService() {
    private val alive = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var upstream: Network? = null
    @Volatile private var tunRef: ParcelFileDescriptor? = null
    @Volatile private var relayRef: PcapRelay? = null
    @Volatile private var pcapRef: PcapFile? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            if (!alive.get()) stopSelf()
            return START_NOT_STICKY
        }
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg.isBlank()) {
            message = "missing package"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive.compareAndSet(false, true)) return START_STICKY
        try {
            startInForeground(pkg)
        } catch (error: Exception) {
            alive.set(false)
            message = error.message ?: "foreground start failed"
            AppLog.w(TAG, "foreground start failed", error)
            stopSelf()
            return START_NOT_STICKY
        }
        targetPackage = pkg
        message = ""
        packets = 0
        bytes = 0
        running = true
        Thread({ runCapture(pkg) }, "pcap-tun").start()
        return START_STICKY
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun runCapture(pkg: String) {
        var tun: ParcelFileDescriptor? = null
        var pcap: PcapFile? = null
        var relay: PcapRelay? = null
        try {
            upstream = activeUpstream()
            tun = openTun(pkg) ?: throw IllegalStateException("VPN was not granted")
            tunRef = tun
            val file = pcapFile(pkg)
            path = file.absolutePath
            pcap = PcapFile(file)
            pcapRef = pcap
            val output = FileOutputStream(tun.fileDescriptor)
            fun writeTun(packet: ByteArray) {
                synchronized(writeLock) {
                    output.write(packet)
                    if (!pcap.write(packet)) alive.set(false)
                }
                packets = pcap.packets
                bytes = pcap.sizeBytes()
            }
            relay = PcapRelay(
                outsideTcp = ::pinOutside,
                outsideUdp = ::pinOutside,
                writeTun = ::writeTun,
            )
            relayRef = relay
            AppLog.i(TAG, "capture $pkg -> ${file.name}")
            val input = FileInputStream(tun.fileDescriptor)
            val buf = ByteArray(32767)
            while (alive.get()) {
                val n = input.read(buf)
                if (n <= 0) break
                val packet = buf.copyOf(n)
                if (!pcap.write(packet)) {
                    message = "stopped at 50 MB"
                    break
                }
                packets = pcap.packets
                bytes = pcap.sizeBytes()
                relay.onPacket(packet)
            }
            if (pcap.capped && message.isEmpty()) message = "stopped at 50 MB"
        } catch (error: Exception) {
            val stopping = !alive.get() || error is InterruptedIOException
            if (!stopping && message.isEmpty()) message = error.message ?: "capture stopped"
            if (stopping) AppLog.i(TAG, "capture stopped") else AppLog.w(TAG, "capture ended", error)
        } finally {
            alive.set(false)
            running = false
            relay?.close()
            relayRef = null
            upstream = null
            runCatching { pcap?.close() }
            pcapRef = null
            runCatching { tun?.close() }
            tunRef = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun activeUpstream(): Network? =
        getSystemService(ConnectivityManager::class.java)?.activeNetwork

    private fun openTun(pkg: String): ParcelFileDescriptor? {
        val link = upstream?.let { getSystemService(ConnectivityManager::class.java)?.getLinkProperties(it) }
        val ipv6 = hasGlobalIpv6(link)
        val dns = dnsServers(link, ipv6)
        val builder = Builder()
            .setSession("Magnon $pkg")
            .setMtu(1280)
            .setBlocking(true)
            .addAddress("10.215.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setMetered(false)
        if (ipv6) {
            builder.addAddress("fd00:215::2", 128).addRoute("::", 0)
        }
        dns.forEach { builder.addDnsServer(it) }
        builder.addAllowedApplication(pkg)
        AppLog.i(TAG, "tun ipv6=$ipv6 dns=$dns")
        return builder.establish()
    }

    private fun hasGlobalIpv6(link: LinkProperties?): Boolean =
        link?.linkAddresses?.any { info ->
            val addr = info.address
            addr is Inet6Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress
        } == true

    private fun dnsServers(link: LinkProperties?, ipv6: Boolean): List<String> {
        val fromLink = link?.dnsServers.orEmpty().mapNotNull { addr ->
            if (addr.isLinkLocalAddress || addr.isLoopbackAddress || addr.isAnyLocalAddress) return@mapNotNull null
            if (!ipv6 && addr is Inet6Address) return@mapNotNull null
            addr.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() }
        }.distinct()
        return (if (fromLink.isNotEmpty()) fromLink else listOf("1.1.1.1")).take(4)
    }

    private fun pinOutside(socket: Socket) {
        val network = upstream
        if (network != null) {
            try {
                network.bindSocket(socket)
                return
            } catch (error: Exception) {
                AppLog.w(TAG, "bind tcp: ${error.message}")
            }
        }
        if (!protect(socket)) AppLog.w(TAG, "protect tcp failed")
    }

    private fun pinOutside(socket: DatagramSocket) {
        val network = upstream
        if (network != null) {
            try {
                network.bindSocket(socket)
                return
            } catch (error: Exception) {
                AppLog.w(TAG, "bind udp: ${error.message}")
            }
        }
        if (!protect(socket)) AppLog.w(TAG, "protect udp failed")
    }

    private fun pcapFile(pkg: String): File {
        val dir = getExternalFilesDir("pcap") ?: File(filesDir, "pcap")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        return File(dir, "$safe-$stamp.pcap")
    }

    private fun startInForeground(pkg: String) {
        val note = notification(pkg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, note)
        }
    }

    private fun stopCapture() {
        alive.set(false)
        relayRef?.close()
        runCatching { tunRef?.close() }
    }

    private fun notification(pkg: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Packet capture", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows which app is being recorded"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Capturing $pkg")
            .setContentText("Traffic stays on this phone")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MagnonPcap"
        private const val CHANNEL = "magnon-pcap"
        private const val NOTIFICATION_ID = 47
        private const val EXTRA_PACKAGE = "package"
        private const val ACTION_STOP = "stop"

        @Volatile var running = false
        @Volatile var targetPackage = ""
        @Volatile var packets = 0L
        @Volatile var bytes = 0L
        @Volatile var path = ""
        @Volatile var message = ""

        fun start(context: Context, packageName: String) {
            val intent = Intent(context, PcapVpnService::class.java).putExtra(EXTRA_PACKAGE, packageName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PcapVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

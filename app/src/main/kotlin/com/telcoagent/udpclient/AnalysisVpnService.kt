package com.telcoagent.udpclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Foreground service that manages the WireGuard VPN tunnel lifecycle.
 * Uses GoBackend with built-in split tunneling via Interface.Builder.includeApplication().
 */
class AnalysisVpnService : LifecycleService() {

    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private var manageJob: Job? = null
    private var backend: GoBackend? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        backend = GoBackend(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> {
                if (!isConnected()) connectVpn()
            }
            ACTION_DISCONNECT -> disconnectVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manageJob?.cancel()
        supervisorJob.cancel()
        backend = null
        super.onDestroy()
    }

    private fun connectVpn() {
        manageJob?.cancel()
        _state.value = VpnState(active = true, statusText = "Connecting…")
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to WireGuard…"))

        manageJob = serviceScope.launch {
            try {
                val config = buildConfig()
                if (config == null) {
                    _state.value = VpnState(active = false, statusText = "Invalid config — check fields")
                    delay(2000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                val currentBackend = backend ?: GoBackend(this@AnalysisVpnService)
                currentBackend.setState(tunnel, Tunnel.State.UP, config)

                val ctx = this@AnalysisVpnService
                val splitEnabled = AnalysisPreferences.getSplitTunnelEnabled(ctx)
                val splitApps = AnalysisPreferences.getSplitTunnelApps(ctx)

                _state.value = VpnState(
                    active = true,
                    connected = true,
                    statusText = if (splitEnabled && splitApps.isNotEmpty()) {
                        "Connected · ${splitApps.size} apps via VPN"
                    } else {
                        "Connected"
                    },
                    connectedSince = System.currentTimeMillis(),
                    serverAddress = AnalysisPreferences.getServerAddress(ctx),
                    serverPort = AnalysisPreferences.getServerPort(ctx),
                )
                updateNotification(
                    if (splitEnabled && splitApps.isNotEmpty()) {
                        "Connected · ${splitApps.size} apps via VPN"
                    } else {
                        "Connected to ${AnalysisPreferences.getServerAddress(ctx)}"
                    }
                )

                while (true) {
                    delay(5000)
                    try {
                        val currentState = currentBackend.getState(tunnel)
                        if (currentState == Tunnel.State.DOWN) {
                            _state.value = _state.value.copy(connected = false, statusText = "Disconnected by system")
                            break
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = VpnState(active = false, statusText = "Error: ${e.message}")
                updateNotification("Connection failed: ${e.message}")
                delay(3000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun disconnectVpn() {
        manageJob?.cancel()
        manageJob = null

        try {
            val currentBackend = backend
            if (currentBackend != null) {
                try {
                    val currentState = currentBackend.getState(tunnel)
                    if (currentState == Tunnel.State.UP) {
                        val config = buildConfig()
                        if (config != null) {
                            currentBackend.setState(tunnel, Tunnel.State.DOWN, config)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        _state.value = VpnState(active = false, statusText = "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildConfig(): Config? {
        val ctx = this
        if (!AnalysisPreferences.hasConfig(ctx)) return null

        val serverAddress = AnalysisPreferences.getServerAddress(ctx)
        val serverPort = AnalysisPreferences.getServerPort(ctx)
        val serverPubKey = AnalysisPreferences.getServerPublicKey(ctx)
        val clientPrivKey = AnalysisPreferences.getClientPrivateKey(ctx)
        val clientAddress = AnalysisPreferences.getClientAddress(ctx)
        val allowedIps = AnalysisPreferences.getAllowedIps(ctx)
        val dnsServers = AnalysisPreferences.getDnsServers(ctx)
        val splitEnabled = AnalysisPreferences.getSplitTunnelEnabled(ctx)
        val splitApps = AnalysisPreferences.getSplitTunnelApps(ctx)

        if (serverAddress.isBlank() || serverPubKey.isBlank() || clientPrivKey.isBlank()) return null

        return try {
            val ifaceBuilder = com.wireguard.config.Interface.Builder()
                .parsePrivateKey(clientPrivKey)
                .parseAddresses(clientAddress)
                .parseMtu(AnalysisPreferences.getMtu(ctx).toString())
            for (dns in dnsServers.split(",").map { it.trim() }) {
                if (dns.isNotBlank()) {
                    for (addr in InetAddress.getAllByName(dns)) {
                        ifaceBuilder.addDnsServer(addr)
                    }
                }
            }

            // Add split tunneling: include only selected apps
            if (splitEnabled && splitApps.isNotEmpty()) {
                ifaceBuilder.includeApplications(splitApps)
            }

            val iface = ifaceBuilder.build()

            val peerBuilder = com.wireguard.config.Peer.Builder()
                .parsePublicKey(serverPubKey)
                .parseEndpoint("$serverAddress:$serverPort")
                .parsePersistentKeepalive("25")
            val allowedIpList = allowedIps.split(",").map { it.trim() }.filter { it.isNotBlank() }.map { com.wireguard.config.InetNetwork.parse(it) }
            peerBuilder.addAllowedIps(allowedIpList)
            val peer = peerBuilder.build()

            Config.Builder()
                .setInterface(iface)
                .addPeer(peer)
                .build()
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "WireGuard VPN", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "NetProbe WireGuard VPN analysis tunnel" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = android.app.PendingIntent.getService(
            this, 3,
            Intent(this, AnalysisVpnService::class.java).setAction(ACTION_DISCONNECT),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NetProbe Analysis")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_CONNECT = "com.telcoagent.udpclient.action.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.telcoagent.udpclient.action.VPN_DISCONNECT"

        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "analysis_vpn"
        private const val TUNNEL_NAME = "netprobe-analysis"

        val tunnel = object : Tunnel {
            override fun getName(): String = TUNNEL_NAME
            override fun onStateChange(newState: Tunnel.State) {}
        }

        private val _state = MutableStateFlow(VpnState())
        val state: StateFlow<VpnState> = _state.asStateFlow()

        fun isConnected(): Boolean = _state.value.connected

        fun connect(context: Context) {
            val intent = Intent(context, AnalysisVpnService::class.java).setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, AnalysisVpnService::class.java).setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }

        fun generateKeyPair(): Pair<String, String> {
            val privateKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(privateKey)
            privateKey[0] = (privateKey[0].toInt() and 248).toByte()
            privateKey[31] = (privateKey[31].toInt() and 127).toByte()
            privateKey[31] = (privateKey[31].toInt() or 64).toByte()

            val publicKey = Curve25519.scalarMultBase(privateKey)
            return Pair(
                android.util.Base64.encodeToString(privateKey, android.util.Base64.NO_WRAP),
                android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP),
            )
        }
    }
}

data class VpnState(
    val active: Boolean = false,
    val connected: Boolean = false,
    val statusText: String = "Disconnected",
    val connectedSince: Long? = null,
    val serverAddress: String? = null,
    val serverPort: Int? = null,
    val bytesRx: Long? = null,
    val bytesTx: Long? = null,
)

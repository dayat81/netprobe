package com.aksa.magnon

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/**
 * Shared gateway IP + RTT samples (IPv4 and IPv6) for MEASURE ticks and the Speed UI.
 *
 * Traceroute resolve runs **once** at [start] and again only when the path changes
 * (Wi‑Fi ↔ cell, iface, or default-data SIM). [sample] ICMP-pings the cached
 * addresses so the Speed tab can run a rapid pps / loss loop.
 */
object GatewayMonitor {
    private const val TAG = "MySignalGw"
    private const val RESOLVE_DEBOUNCE_MS = 800L

    data class Sample(
        val ipv4: String? = null,
        val ipv4RttMs: Double? = null,
        val ipv6: String? = null,
        val ipv6RttMs: Double? = null,
        val mtu: Int = 1500,
    )

    /** Parallel v4/v6 ICMP floods for capacity / loss probing. */
    data class Burst(
        val ipv4: String? = null,
        val ipv6: String? = null,
        val rtt4: List<Double?> = emptyList(),
        val rtt6: List<Double?> = emptyList(),
        val sent4: Int = 0,
        val recv4: Int = 0,
        val sent6: Int = 0,
        val recv6: Int = 0,
        val pps4: Double = 0.0,
        val pps6: Double = 0.0,
        val workers: Int = 0,
        val durationMs: Long = 0L,
        val startedAtMs: Long = System.currentTimeMillis(),
    )

    @Volatile
    var last: Sample = Sample()
        private set

    @Volatile
    private var pathKey: String? = null

    @Volatile
    private var cachedV4: String? = null

    @Volatile
    private var cachedV6: String? = null

    @Volatile
    private var cachedMtu: Int = 1500

    private val cacheLock = Any()
    private val resolveLock = Any()
    private val watching = AtomicBoolean(false)
    private var appContext: Context? = null
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var subscriptionsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pathListeners = mutableListOf<() -> Unit>()
    private val resolveRunnable = Runnable {
        val ctx = appContext ?: return@Runnable
        Thread { resolveNow(ctx, reason = "debounce") }.start()
    }

    /** Fired on main thread after a real path fingerprint change (wifi/cell/SIM/IP). */
    fun addPathListener(listener: () -> Unit) {
        synchronized(pathListeners) {
            if (listener !in pathListeners) pathListeners += listener
        }
    }

    fun removePathListener(listener: () -> Unit) {
        synchronized(pathListeners) { pathListeners.remove(listener) }
    }

    /** Register network / SIM listeners and resolve once. Idempotent. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!watching.compareAndSet(false, true)) {
            if (pathKey == null) {
                Thread { resolveNow(app, reason = "start-retry") }.start()
            }
            return
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivity = cm
        if (cm != null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleResolve("net-available")
                override fun onLost(network: Network) = scheduleResolve("net-lost")
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    // Transport / validated changes (wifi↔cell) — debounce traceroute.
                    scheduleResolve("net-caps")
                }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    scheduleResolve("net-link")
                }
            }
            networkCallback = cb
            runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    cb,
                )
            }.onFailure { AppLog.w(TAG, "registerNetworkCallback failed", it) }
        }
        val sm = app.getSystemService(SubscriptionManager::class.java)
        if (sm != null) {
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() = scheduleResolve("sim-change")
            }
            subscriptionsListener = listener
            runCatching { sm.addOnSubscriptionsChangedListener(listener) }
                .onFailure { AppLog.w(TAG, "subscriptions listener failed", it) }
        }
        Thread { resolveNow(app, reason = "start") }.start()
        AppLog.i(TAG, "watch started")
    }

    fun stop() {
        if (!watching.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(resolveRunnable)
        networkCallback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        subscriptionsListener?.let { listener ->
            val sm = appContext?.getSystemService(SubscriptionManager::class.java)
            runCatching { sm?.removeOnSubscriptionsChangedListener(listener) }
        }
        subscriptionsListener = null
        connectivity = null
        AppLog.i(TAG, "watch stopped")
    }

    /**
     * Ping cached gateways. If the path fingerprint changed (or never resolved),
     * runs traceroute once first — not on a timer.
     */
    fun sample(context: Context, forceResolve: Boolean = false): Sample {
        val app = context.applicationContext
        val key = pathFingerprint(app)
        if (forceResolve || pathKey == null || pathKey != key) {
            resolveNow(app, reason = if (forceResolve) "force" else "path-mismatch", force = forceResolve)
        }
        return pingCached(app)
    }

    /**
     * Maximal ~[durationMs] ICMP flood on GW4+GW6 (parallel). Speed tab raises
     * [workers] until a little loss appears — that pps is the capacity edge.
     */
    fun flood(
        context: Context,
        durationMs: Long = 10_000L,
        workers: Int = 16,
    ): Burst {
        val app = context.applicationContext
        val key = pathFingerprint(app)
        if (pathKey == null || pathKey != key) {
            resolveNow(app, reason = "path-mismatch")
        }
        val v4: String?
        val v6: String?
        val mtu: Int
        synchronized(cacheLock) {
            v4 = cachedV4
            v6 = cachedV6
            mtu = cachedMtu.takeIf { it >= 576 } ?: GatewayPinger.activeMtu(app)
        }
        val p4 = GatewayPinger.payloadForMtu(mtu, ipv6 = false)
        val p6 = GatewayPinger.payloadForMtu(mtu, ipv6 = true)
        val started = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val f4 = pool.submit(
                Callable {
                    if (v4 != null) {
                        GatewayPinger.pingFlood(v4, durationMs, workers, p4)
                    } else {
                        GatewayPinger.FloodResult(0, 0, emptyList(), durationMs)
                    }
                },
            )
            val f6 = pool.submit(
                Callable {
                    if (v6 != null) {
                        GatewayPinger.pingFlood(v6, durationMs, workers, p6)
                    } else {
                        GatewayPinger.FloodResult(0, 0, emptyList(), durationMs)
                    }
                },
            )
            val waitSec = durationMs / 1_000L + 15L
            val r4 = runCatching {
                f4.get(waitSec, TimeUnit.SECONDS)
            }.getOrElse { GatewayPinger.FloodResult(0, 0, emptyList(), durationMs) }
            val r6 = runCatching {
                f6.get(waitSec, TimeUnit.SECONDS)
            }.getOrElse { GatewayPinger.FloodResult(0, 0, emptyList(), durationMs) }
            val last4 = r4.rtts.lastOrNull()
            val last6 = r6.rtts.lastOrNull()
            synchronized(cacheLock) {
                last = Sample(ipv4 = v4, ipv4RttMs = last4, ipv6 = v6, ipv6RttMs = last6, mtu = mtu)
            }
            AppLog.i(
                TAG,
                "flood ${durationMs}ms workers=$workers " +
                    "gw4=${v4 ?: "—"} sent4=${r4.sent} recv4=${r4.received} " +
                    "pps4=${"%.1f".format(r4.pps)} loss4=${GatewayPinger.formatLoss(r4.lost, r4.sent)} " +
                    "gw6=${v6 ?: "—"} sent6=${r6.sent} recv6=${r6.received} " +
                    "pps6=${"%.1f".format(r6.pps)} loss6=${GatewayPinger.formatLoss(r6.lost, r6.sent)}",
            )
            return Burst(
                ipv4 = v4,
                ipv6 = v6,
                rtt4 = r4.slots(),
                rtt6 = r6.slots(),
                sent4 = r4.sent,
                recv4 = r4.received,
                sent6 = r6.sent,
                recv6 = r6.received,
                pps4 = r4.pps,
                pps6 = r6.pps,
                workers = workers,
                durationMs = durationMs,
                startedAtMs = started,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun scheduleResolve(reason: String) {
        // Debounce only — resolveNow no-ops unless path fingerprint actually changed
        // (wifi↔cell, iface, default-data SIM, local IPv4).
        mainHandler.removeCallbacks(resolveRunnable)
        mainHandler.postDelayed(resolveRunnable, RESOLVE_DEBOUNCE_MS)
        AppLog.d(TAG, "path check queued ($reason)")
    }

    private fun resolveNow(context: Context, reason: String, force: Boolean = false) {
        var changed = false
        synchronized(resolveLock) {
            val key = pathFingerprint(context)
            if (!force && key == pathKey && pathKey != null) return

            val v4 = ClientTrace.gatewayIp(context).takeIf { it.isNotBlank() && it != "—" }
            val v6 = ClientTrace.gatewayIpv6(context).takeIf { it.isNotBlank() && it != "—" }
            val mtu = GatewayPinger.activeMtu(context)
            synchronized(cacheLock) {
                changed = pathKey != null && pathKey != key
                cachedV4 = v4
                cachedV6 = v6
                cachedMtu = mtu
                pathKey = key
                last = Sample(ipv4 = v4, ipv6 = v6, mtu = mtu)
            }
            AppLog.i(TAG, "resolve($reason) path=$key mtu=$mtu gw4=${v4 ?: "—"} gw6=${v6 ?: "—"}")
        }
        if (changed) {
            val copy = synchronized(pathListeners) { pathListeners.toList() }
            if (copy.isNotEmpty()) {
                mainHandler.post { copy.forEach { runCatching { it() } } }
            }
        }
    }

    private fun pingCached(context: Context): Sample {
        val v4: String?
        val v6: String?
        val mtu: Int
        synchronized(cacheLock) {
            v4 = cachedV4
            v6 = cachedV6
            mtu = cachedMtu.takeIf { it >= 576 } ?: GatewayPinger.activeMtu(context)
        }
        val v4Payload = GatewayPinger.payloadForMtu(mtu, ipv6 = false)
        val v6Payload = GatewayPinger.payloadForMtu(mtu, ipv6 = true)
        val v4Rtt = if (v4 != null) GatewayPinger.pingOnce(v4, payloadBytes = v4Payload) else null
        val v6Rtt = if (v6 != null) GatewayPinger.pingOnce(v6, payloadBytes = v6Payload) else null
        val sample = Sample(
            ipv4 = v4,
            ipv4RttMs = v4Rtt,
            ipv6 = v6,
            ipv6RttMs = v6Rtt,
            mtu = mtu,
        )
        synchronized(cacheLock) { last = sample }
        if ((v4 != null && v4Rtt == null) || (v6 != null && v6Rtt == null)) {
            AppLog.i(
                TAG,
                "ping loss gw4=${v4 ?: "—"} rtt4=${GatewayPinger.formatRtt(v4Rtt)} " +
                    "gw6=${v6 ?: "—"} rtt6=${GatewayPinger.formatRtt(v6Rtt)}",
            )
        }
        return sample
    }

    /** wifi|cell|other + iface + default-data sub + local IPv4. */
    private fun pathFingerprint(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val props = network?.let { cm.getLinkProperties(it) }
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "eth"
            network == null -> "none"
            else -> "other"
        }
        val iface = props?.interfaceName.orEmpty()
        val subId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val local4 = ClientNetworkInfo.localAddressesPair(context).ipv4.orEmpty()
        return "$transport|$iface|sub$subId|$local4"
    }
}

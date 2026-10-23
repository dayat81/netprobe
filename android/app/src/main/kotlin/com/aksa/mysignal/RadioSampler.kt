package com.aksa.mysignal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds one [RadioSnapshot] per call, scoped per active subscription.
 *
 * Telephony callbacks (display info, service state, signal strength, physical channel
 * configuration) only cache the latest value; [sample] reads them together with fresh
 * synchronous getters so a 3 s tick never waits on the modem.
 */
class RadioSampler(private val context: Context) {
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)
    private val subscriptionManager: SubscriptionManager? =
        context.getSystemService(SubscriptionManager::class.java)
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val locations: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val thread = HandlerThread("mysignal-telephony").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val trackers = ConcurrentHashMap<Int, SubTracker>()
    private val random = SecureRandom()
    private val device: DeviceInfo by lazy { readDevice() }
    @Volatile private var lastWifiScanRequestMs = 0L
    @Volatile private var preferApnCache: Pair<Long, List<PreferApn>>? = null
    @Volatile private var lastFix: Location? = null
    @Volatile private var locationListening = false

    private val locationListener = LocationListener { location ->
        val prev = lastFix
        if (prev == null || location.time >= prev.time) lastFix = location
    }

    fun hasCorePermissions(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.READ_PHONE_STATE)

    fun hasPrecisePermission(): Boolean = granted(Manifest.permission.READ_PRECISE_PHONE_STATE)

    fun close() {
        stopLocationUpdates()
        trackers.values.forEach { it.unregister() }
        trackers.clear()
        thread.quitSafely()
    }

    fun sample(): RadioSnapshot {
        val now = System.currentTimeMillis()
        val eventId = "mys" + randomHex(16)
        if (!hasCorePermissions()) {
            return RadioSnapshot(
                eventId, now, device, emptyList(), emptyList(), network(), location(), wifi(),
                error = "Need Location + Phone permission",
            )
        }
        ensureLocationUpdates()
        syncTrackers()
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        val ordered = trackers.values.sortedWith(compareBy({ it.slot }, { it.subId }))
        val subs = ordered.map { subscription(it, defaultData) }
        val cells = cells(ordered)
        return RadioSnapshot(eventId, now, device, subs, cells, network(), location(), wifi())
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun syncTrackers() {
        val base = telephony ?: return
        val active: List<SubscriptionInfo> = try {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val wanted = LinkedHashMap<Int, SubscriptionInfo?>()
        active.take(8).forEach { wanted[it.subscriptionId] = it }
        if (wanted.isEmpty()) wanted[NO_SUB] = null
        trackers.keys.filter { it !in wanted }.forEach { trackers.remove(it)?.unregister() }
        wanted.forEach { (id, info) ->
            val existing = trackers[id]
            if (existing != null) {
                existing.info = info
                existing.ensurePhysical()
                return@forEach
            }
            val scoped = if (id == NO_SUB) base else base.createForSubscriptionId(id)
            val tracker = SubTracker(id, info, scoped)
            trackers[id] = tracker
            tracker.register()
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscription(t: SubTracker, defaultData: Int): SubscriptionRadio {
        val tm = t.tm
        val service = runCatching { tm.serviceState }.getOrNull() ?: t.service
        val registration = psRegistration(service)
        val registrationText = registration?.toString()
        val serviceText = service?.toString()
        val dataType = runCatching { tm.dataNetworkType }.getOrDefault(0)
        val voiceType = runCatching { tm.voiceNetworkType }.getOrDefault(0)
        val networkType = when {
            dataType > 0 -> dataType
            registration != null && registration.accessNetworkTechnology > 0 -> registration.accessNetworkTechnology
            else -> 0
        }
        val carriers = t.carriers()
        val nr = RadioRules.selectNrState(
            hiddenInt(service, "getNrState")
                ?: hiddenField(service, "mNrState")
                ?: hiddenInt(registration, "getNrState")
                ?: hiddenField(registration, "mNrState"),
            RadioRules.parseNrState(serviceText) ?: RadioRules.parseNrState(registrationText),
            carriers,
            networkType,
        )
        if (nr.value == null && loggedMiss.add("nr-text")) {
            val at = serviceText?.indexOf("nrState=") ?: -1
            val slice = if (serviceText != null && at >= 0) {
                serviceText.substring(at, minOf(at + 28, serviceText.length))
            } else {
                "absent"
            }
            Log.i(TAG, "nrState unresolved text=$slice")
        }
        val override = t.display?.overrideNetworkType
        val ca = RadioRules.deriveCarrierAggregation(
            networkType,
            override,
            RadioRules.parseCarrierAggregation(serviceText),
            RadioRules.parseCarrierAggregation(registrationText),
            carriers,
        )
        val nrArfcn = carriers.firstOrNull { it.rat == "NR" }?.channel
            ?: t.cellInfos().filterIsInstance<CellInfoNr>().firstOrNull { it.isRegistered }
                ?.let { (it.cellIdentity as CellIdentityNr).nrarfcn }
        val reportedRange = hiddenInt(service, "getNrFrequencyRange")?.takeIf { it in 0..4 }
            ?: RadioRules.parseNrFrequencyRange(serviceText)
        val nrRange = reportedRange?.takeIf { it > 0 }
            ?: RadioRules.nrFrequencyRangeFromArfcn(nrArfcn)
            ?: reportedRange
        val info = t.info
        val ids = t.identifiers()
        val subId = if (t.subId == NO_SUB) null else t.subId
        val prefer = preferApn(subId, defaultData)
        return SubscriptionRadio(
            slot = info?.simSlotIndex ?: 0,
            subId = subId,
            carrier = RadioRules.clean(info?.carrierName, 64),
            mcc = RadioRules.clean(info?.mccString, 4),
            mnc = RadioRules.clean(info?.mncString, 4),
            rat = RadioRules.ratName(if (networkType > 0) networkType else voiceType),
            networkOperator = RadioRules.clean(runCatching { tm.networkOperatorName }.getOrNull(), 64),
            simOperator = RadioRules.clean(runCatching { tm.simOperatorName }.getOrNull(), 64),
            dataState = dataState(tm),
            serviceState = serviceStateName(service),
            isDefaultData = t.subId != NO_SUB && t.subId == defaultData,
            nrState = nr.value,
            nrStateSource = nr.source,
            nrFrequencyRange = nrRange,
            networkType = networkType,
            voiceNetworkType = voiceType,
            overrideNetworkType = override,
            ca = ca.value,
            caSource = ca.source,
            endcAvailable = RadioRules.parseEndcAvailable(registrationText)
                ?: RadioRules.parseEndcAvailable(serviceText),
            channel = RadioRules.parseChannelNumber(serviceText)
                ?: service?.channelNumber?.let { RadioRules.bounded(it, 1, 10_000_000) },
            servingSignal = servingSignal(runCatching { tm.signalStrength }.getOrNull() ?: t.signal, networkType),
            carriers = carriers,
            imei = ids.imei,
            imsi = ids.imsi,
            msisdn = ids.msisdn,
            iccid = ids.iccid,
            apn = prefer?.apn.orEmpty(),
            apnName = prefer?.name.orEmpty(),
            apnTypes = prefer?.types.orEmpty(),
            apnProtocol = prefer?.protocol.orEmpty(),
            cellBandwidthKhz = RadioRules.cellBandwidthsKhz(serviceText),
        )
    }

    /** Preferred APN for this subscription. Provider access is blocked on many OEMs; fall back to NetworkInfo.extraInfo. */
    private fun preferApn(subId: Int?, defaultData: Int): PreferApn? {
        val rows = preferApnRows()
        if (rows.isNotEmpty()) {
            if (subId != null) {
                rows.firstOrNull { it.subId == subId }?.let { return it }
            }
            if (subId != null && subId == defaultData) {
                rows.firstOrNull { it.subId == null || it.subId == -1 || it.subId == defaultData }?.let { return it }
            }
            if (subId == null || subId == defaultData) return rows.firstOrNull()
        }
        if (subId == null || subId == defaultData) {
            activeDataApn()?.let { return it }
        }
        return null
    }

    /**
     * APN from cellular NetworkInfo.extraInfo. Prefer the INTERNET PDN even when Wi-Fi is the
     * default route (activeNetwork would then be Wi-Fi and hide the APN).
     */
    @Suppress("DEPRECATION")
    private fun activeDataApn(): PreferApn? {
        val cm = connectivity ?: return null
        return try {
            var best: PreferApn? = null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val info = cm.getNetworkInfo(network) ?: continue
                if (!info.isConnected) continue
                val extra = RadioRules.clean(info.extraInfo, 64)
                if (extra.isEmpty() || extra.equals("ims", ignoreCase = true)) continue
                val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val row = PreferApn(apn = extra, name = extra, types = if (internet) "default" else info.typeName.orEmpty(), subId = null)
                if (internet) {
                    if (loggedMiss.add("apn-extra")) Log.i(TAG, "apn via NetworkInfo.extraInfo=$extra")
                    return row
                }
                if (best == null) best = row
            }
            best?.also {
                if (loggedMiss.add("apn-extra")) Log.i(TAG, "apn via NetworkInfo.extraInfo=${it.apn} (non-internet)")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun preferApnRows(): List<PreferApn> {
        preferApnCache?.let { (at, rows) ->
            if (System.currentTimeMillis() - at < APN_CACHE_MS) return rows
        }
        val rows = ArrayList<PreferApn>()
        try {
            context.contentResolver.query(
                Uri.parse("content://telephony/carriers/preferapn"),
                arrayOf("name", "apn", "type", "protocol", "numeric", "sub_id"),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val apnIdx = cursor.getColumnIndex("apn")
                val typeIdx = cursor.getColumnIndex("type")
                val protoIdx = cursor.getColumnIndex("protocol")
                val numericIdx = cursor.getColumnIndex("numeric")
                val subIdx = cursor.getColumnIndex("sub_id")
                while (cursor.moveToNext()) {
                    val apn = if (apnIdx >= 0) cursor.getString(apnIdx).orEmpty() else ""
                    if (apn.isBlank()) continue
                    rows += PreferApn(
                        apn = RadioRules.clean(apn, 64),
                        name = RadioRules.clean(if (nameIdx >= 0) cursor.getString(nameIdx) else "", 64),
                        types = RadioRules.clean(if (typeIdx >= 0) cursor.getString(typeIdx) else "", 64),
                        protocol = RadioRules.clean(if (protoIdx >= 0) cursor.getString(protoIdx) else "", 16),
                        numeric = RadioRules.clean(if (numericIdx >= 0) cursor.getString(numericIdx) else "", 8),
                        subId = if (subIdx >= 0 && !cursor.isNull(subIdx)) cursor.getInt(subIdx) else null,
                    )
                }
            }
        } catch (e: SecurityException) {
            if (loggedMiss.add("preferapn")) Log.i(TAG, "preferapn denied: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            if (loggedMiss.add("preferapn")) Log.i(TAG, "preferapn failed: ${e.javaClass.simpleName}")
        }
        preferApnCache = System.currentTimeMillis() to rows
        if (rows.isNotEmpty() && loggedMiss.add("preferapn-ok")) {
            Log.i(TAG, "preferapn ${rows.joinToString { "${it.apn}(sub=${it.subId})" }}")
        }
        return rows
    }

    /**
     * Hidden ServiceState/NetworkRegistrationInfo getter, same call as the OpenSignal mirror.
     * Xiaomi hides getNrState from public lookup, so a one-time VMRuntime exemption runs first
     * and the declared method is used when getMethod cannot see it.
     */
    private fun hiddenInt(target: Any?, method: String): Int? {
        if (target == null) return null
        exemptHiddenApi()
        val value = try {
            target.javaClass.getMethod(method).invoke(target)
        } catch (_: Exception) {
            try {
                HiddenApiBypass.invoke(target.javaClass, target, method)
            } catch (e: Throwable) {
                if (loggedMiss.add(method)) {
                    Log.i(TAG, "$method unavailable on ${target.javaClass.name}: ${e.javaClass.simpleName}")
                }
                null
            }
        }
        return value as? Int
    }

    private fun hiddenField(target: Any?, name: String): Int? {
        if (target == null) return null
        exemptHiddenApi()
        return try {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.getInt(target)
        } catch (e: Exception) {
            if (loggedMiss.add(name)) Log.i(TAG, "$name unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun exemptHiddenApi() {
        if (!exempted.compareAndSet(false, true)) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/telephony/")
            Log.i(TAG, "hidden api exemption set")
        } catch (e: Throwable) {
            Log.w(TAG, "hidden api exemption failed: ${e.javaClass.simpleName} ${e.cause?.message ?: e.message}")
        }
    }

    /** PS-domain WWAN registration: the entry whose nrState matches what CORE stores. */
    private fun psRegistration(service: ServiceState?): NetworkRegistrationInfo? {
        val list = service?.networkRegistrationInfoList ?: return null
        return list.firstOrNull {
            it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                (it.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0
        }
    }

    private fun cells(trackers: List<SubTracker>): List<RadioCell> {
        val serving = ArrayList<RadioCell>()
        val neighbors = ArrayList<RadioCell>()
        val seen = HashSet<String>()
        trackers.forEach { t ->
            val slot = t.info?.simSlotIndex
            val subId = if (t.subId == NO_SUB) null else t.subId
            val carrier = RadioRules.clean(t.info?.carrierName, 64)
            t.requestCellUpdate()
            fun add(cell: RadioCell) {
                val key = "${cell.subId}|${cell.rat}|${cell.channel}|${cell.pci}|${cell.chartServing}"
                if (!seen.add(key)) return
                if (cell.chartServing) serving += cell else neighbors += cell
            }
            t.cellInfos().forEach { info ->
                runCatching { parseCell(info, subId, slot, carrier) }.getOrNull()?.let(::add)
            }
            registrationCells(t, subId, slot, carrier).forEach(::add)
        }
        neighbors.sortByDescending { it.ssRsrp ?: it.rsrp ?: it.dbm ?: Int.MIN_VALUE }
        return RadioRules.fillServingSinr(serving + neighbors, sinrBySub(trackers)).take(RadioSnapshot.MAX_CELLS)
    }

    /** Camped-cell SINR from SignalStrength, keyed by subscription and RAT. */
    private fun sinrBySub(trackers: List<SubTracker>): Map<Pair<Int?, String>, Int> {
        val out = LinkedHashMap<Pair<Int?, String>, Int>()
        trackers.forEach { tracker ->
            val subId = if (tracker.subId == NO_SUB) null else tracker.subId
            val strength = runCatching { tracker.tm.signalStrength }.getOrNull() ?: tracker.signal ?: return@forEach
            signalSinr(strength, "LTE")?.let { out[subId to "LTE"] = it }
            signalSinr(strength, "NR")?.let { out[subId to "NR"] = it }
        }
        return out
    }

    private fun signalSinr(strength: SignalStrength, rat: String): Int? {
        val raw = when (rat) {
            "LTE" -> strength.cellSignalStrengths.filterIsInstance<CellSignalStrengthLte>().firstOrNull()?.rssnr
            "NR" -> strength.cellSignalStrengths.filterIsInstance<CellSignalStrengthNr>().firstOrNull()?.ssSinr
            else -> null
        } ?: return null
        return RadioRules.bounded(raw, -30, 50)
    }

    /** PS/CS camped identity. ServiceState updates on its own callback, in parallel with CellInfo. */
    private fun registrationCells(t: SubTracker, subId: Int?, slot: Int?, carrier: String): List<RadioCell> {
        val service = t.service ?: return emptyList()
        val list = service.networkRegistrationInfoList ?: return emptyList()
        return list.mapNotNull { info ->
            if (info.transportType != AccessNetworkConstants.TRANSPORT_TYPE_WWAN) return@mapNotNull null
            val id = info.cellIdentity ?: return@mapNotNull null
            val registered = info.isRegistered
            val connection = if (registered) RadioCell.CONNECTION_PRIMARY else RadioCell.CONNECTION_NONE
            when (id) {
                is CellIdentityLte -> RadioCell(
                    registered = registered,
                    rat = "LTE",
                    mcc = RadioRules.clean(id.mccString, 4),
                    mnc = RadioRules.clean(id.mncString, 4),
                    ci = RadioRules.cellId(id.ci.toLong()),
                    pci = RadioRules.bounded(id.pci, 0, 1007),
                    tac = RadioRules.bounded(id.tac, 0, 16_777_215),
                    earfcn = RadioRules.bounded(id.earfcn, 0, 10_000_000),
                    subId = subId,
                    simSlot = slot,
                    carrier = if (registered) carrier else "",
                    connection = connection,
                )
                is CellIdentityNr -> RadioCell(
                    registered = registered,
                    rat = "NR",
                    mcc = RadioRules.clean(id.mccString, 4),
                    mnc = RadioRules.clean(id.mncString, 4),
                    ci = RadioRules.cellId(id.nci),
                    pci = RadioRules.bounded(id.pci, 0, 1007),
                    tac = RadioRules.bounded(id.tac, 0, 16_777_215),
                    nrarfcn = RadioRules.bounded(id.nrarfcn, 0, 3_279_165),
                    subId = subId,
                    simSlot = slot,
                    carrier = if (registered) carrier else "",
                    connection = connection,
                )
                else -> null
            }
        }
    }

    private fun mergeCellInfos(live: List<CellInfo>, fresh: List<CellInfo>?): List<CellInfo> {
        if (fresh.isNullOrEmpty()) return live
        val best = LinkedHashMap<String, Pair<CellMerge.Item, CellInfo>>()
        fun consider(info: CellInfo, isFresh: Boolean) {
            val item = CellMerge.Item(cellKey(info), cellChannel(info), cellServing(info), isFresh)
            val prev = best[item.id]
            if (prev == null || CellMerge.wins(item, prev.first)) best[item.id] = item to info
        }
        live.forEach { consider(it, false) }
        fresh.forEach { consider(it, true) }
        return best.values.map { it.second }
    }

    private fun cellKey(info: CellInfo): String {
        val id = info.cellIdentity
        val rat = when (info) {
            is CellInfoLte -> "LTE"
            is CellInfoNr -> "NR"
            else -> info.javaClass.simpleName
        }
        val pci = when (id) {
            is CellIdentityLte -> id.pci
            is CellIdentityNr -> id.pci
            else -> -1
        }
        val mcc = when (id) {
            is CellIdentityLte -> id.mccString
            is CellIdentityNr -> id.mccString
            else -> ""
        }
        val mnc = when (id) {
            is CellIdentityLte -> id.mncString
            is CellIdentityNr -> id.mncString
            else -> ""
        }
        return "$rat|$mcc|$mnc|$pci"
    }

    private fun cellChannel(info: CellInfo): Int? = when (val id = info.cellIdentity) {
        is CellIdentityLte -> RadioRules.bounded(id.earfcn, 0, 10_000_000)
        is CellIdentityNr -> RadioRules.bounded(id.nrarfcn, 0, 3_279_165)
        else -> null
    }

    private fun cellServing(info: CellInfo): Boolean {
        val connection = runCatching { info.cellConnectionStatus }.getOrDefault(RadioCell.CONNECTION_NONE)
        return info.isRegistered ||
            connection == RadioCell.CONNECTION_PRIMARY ||
            connection == RadioCell.CONNECTION_SECONDARY
    }

    private fun parseCell(info: CellInfo, subId: Int?, slot: Int?, carrier: String): RadioCell? {
        val registered = info.isRegistered
        val connection = runCatching { info.cellConnectionStatus }.getOrDefault(RadioCell.CONNECTION_NONE)
        val serving = registered || connection == RadioCell.CONNECTION_PRIMARY || connection == RadioCell.CONNECTION_SECONDARY
        val attributed: (RadioCell) -> RadioCell = {
            it.copy(
                subId = subId,
                simSlot = slot,
                carrier = if (serving) carrier else "",
                connection = connection,
            )
        }
        return when (info) {
            is CellInfoLte -> attributed(lte(info, registered))
            is CellInfoNr -> attributed(nr(info, registered))
            is CellInfoWcdma -> attributed(wcdma(info, registered))
            is CellInfoGsm -> attributed(gsm(info, registered))
            is CellInfoTdscdma -> attributed(tdscdma(info, registered))
            is CellInfoCdma -> attributed(cdma(info, registered))
            else -> null
        }
    }

    private fun lte(info: CellInfoLte, registered: Boolean): RadioCell {
        val id = info.cellIdentity
        val sig = info.cellSignalStrength
        return RadioCell(
            registered = registered,
            rat = "LTE",
            mcc = RadioRules.clean(id.mccString, 4),
            mnc = RadioRules.clean(id.mncString, 4),
            ci = RadioRules.cellId(id.ci.toLong()),
            pci = RadioRules.bounded(id.pci, 0, 1007),
            tac = RadioRules.bounded(id.tac, 0, 16_777_215),
            earfcn = RadioRules.bounded(id.earfcn, 0, 10_000_000),
            bands = id.bands.filter { it in 1..300 },
            bandwidthKhz = RadioRules.bounded(id.bandwidth, 1, 400_000),
            rsrp = RadioRules.bounded(sig.rsrp, -200, 0),
            rsrq = RadioRules.bounded(sig.rsrq, -40, 10),
            sinr = RadioRules.bounded(sig.rssnr, -30, 50),
            rssi = RadioRules.bounded(sig.rssi, -200, 0),
            cqi = RadioRules.bounded(sig.cqi, 0, 15),
            timingAdvance = RadioRules.bounded(sig.timingAdvance, 0, 4096),
            dbm = RadioRules.bounded(sig.dbm, -200, 0),
            asu = RadioRules.bounded(sig.asuLevel, 0, 200),
            level = RadioRules.bounded(sig.level, 0, 4),
        )
    }

    private fun nr(info: CellInfoNr, registered: Boolean): RadioCell {
        val id = info.cellIdentity as CellIdentityNr
        val sig = info.cellSignalStrength as CellSignalStrengthNr
        val ssRsrp = RadioRules.bounded(sig.ssRsrp, -200, 0)
        val ssRsrq = RadioRules.bounded(sig.ssRsrq, -40, 20)
        val ssSinr = RadioRules.bounded(sig.ssSinr, -30, 50)
        return RadioCell(
            registered = registered,
            rat = "NR",
            mcc = RadioRules.clean(id.mccString, 4),
            mnc = RadioRules.clean(id.mncString, 4),
            ci = RadioRules.cellId(id.nci),
            pci = RadioRules.bounded(id.pci, 0, 1007),
            tac = RadioRules.bounded(id.tac, 0, 16_777_215),
            nrarfcn = RadioRules.bounded(id.nrarfcn, 0, 3_279_165),
            bands = id.bands.filter { it in 1..300 },
            rsrp = ssRsrp,
            rsrq = ssRsrq?.takeIf { it in -40..10 },
            sinr = ssSinr,
            ssRsrp = ssRsrp,
            ssRsrq = ssRsrq,
            ssSinr = ssSinr,
            csiRsrp = RadioRules.bounded(sig.csiRsrp, -200, 0),
            csiRsrq = RadioRules.bounded(sig.csiRsrq, -40, 20),
            csiSinr = RadioRules.bounded(sig.csiSinr, -30, 50),
            dbm = RadioRules.bounded(sig.dbm, -200, 0),
            asu = RadioRules.bounded(sig.asuLevel, 0, 200),
            level = RadioRules.bounded(sig.level, 0, 4),
        )
    }

    private fun wcdma(info: CellInfoWcdma, registered: Boolean): RadioCell {
        val id = info.cellIdentity
        val sig = info.cellSignalStrength
        return RadioCell(
            registered = registered,
            rat = "WCDMA",
            mcc = RadioRules.clean(id.mccString, 4),
            mnc = RadioRules.clean(id.mncString, 4),
            ci = RadioRules.cellId(id.cid.toLong()),
            pci = RadioRules.bounded(id.psc, 0, 511),
            tac = RadioRules.bounded(id.lac, 0, 65_535),
            uarfcn = RadioRules.bounded(id.uarfcn, 0, 200_000),
            dbm = RadioRules.bounded(sig.dbm, -200, 0),
            asu = RadioRules.bounded(sig.asuLevel, 0, 200),
            level = RadioRules.bounded(sig.level, 0, 4),
        )
    }

    private fun gsm(info: CellInfoGsm, registered: Boolean): RadioCell {
        val id = info.cellIdentity
        val sig = info.cellSignalStrength
        return RadioCell(
            registered = registered,
            rat = "GSM",
            mcc = RadioRules.clean(id.mccString, 4),
            mnc = RadioRules.clean(id.mncString, 4),
            ci = RadioRules.cellId(id.cid.toLong()),
            pci = RadioRules.bounded(id.bsic, 0, 63),
            tac = RadioRules.bounded(id.lac, 0, 65_535),
            arfcn = RadioRules.bounded(id.arfcn, 0, 200_000),
            rssi = RadioRules.bounded(sig.rssi, -200, 0),
            timingAdvance = RadioRules.bounded(sig.timingAdvance, 0, 4096),
            dbm = RadioRules.bounded(sig.dbm, -200, 0),
            asu = RadioRules.bounded(sig.asuLevel, 0, 200),
            level = RadioRules.bounded(sig.level, 0, 4),
        )
    }

    private fun tdscdma(info: CellInfoTdscdma, registered: Boolean): RadioCell {
        val id = info.cellIdentity
        val sig = info.cellSignalStrength
        return RadioCell(
            registered = registered,
            rat = "TDSCDMA",
            mcc = RadioRules.clean(id.mccString, 4),
            mnc = RadioRules.clean(id.mncString, 4),
            ci = RadioRules.cellId(id.cid.toLong()),
            pci = RadioRules.bounded(id.cpid, 0, 1007),
            tac = RadioRules.bounded(id.lac, 0, 65_535),
            uarfcn = RadioRules.bounded(id.uarfcn, 0, 200_000),
            dbm = RadioRules.bounded(sig.dbm, -200, 0),
            asu = RadioRules.bounded(sig.asuLevel, 0, 200),
            level = RadioRules.bounded(sig.level, 0, 4),
        )
    }

    private fun cdma(info: CellInfoCdma, registered: Boolean): RadioCell {
        val id = info.cellIdentity
        val sig = info.cellSignalStrength
        return RadioCell(
            registered = registered,
            rat = "CDMA",
            ci = RadioRules.cellId(id.basestationId.toLong()),
            pci = RadioRules.bounded(id.networkId, 0, 1007),
            tac = RadioRules.bounded(id.systemId, 0, 65_535),
            rssi = RadioRules.bounded(sig.cdmaDbm, -200, 0),
            dbm = RadioRules.bounded(sig.dbm, -200, 0),
            asu = RadioRules.bounded(sig.asuLevel, 0, 200),
            level = RadioRules.bounded(sig.level, 0, 4),
        )
    }

    private fun servingSignal(strength: SignalStrength?, networkType: Int): ServingSignal? {
        val list = strength?.cellSignalStrengths ?: return null
        val nr = list.filterIsInstance<CellSignalStrengthNr>().firstOrNull()
        val lte = list.filterIsInstance<CellSignalStrengthLte>().firstOrNull()
        if (networkType == RadioRules.NETWORK_TYPE_NR && nr != null) return nrSignal(nr)
        if (lte != null) {
            return ServingSignal(
                rat = "LTE",
                rsrp = RadioRules.bounded(lte.rsrp, -200, 0),
                rsrq = RadioRules.bounded(lte.rsrq, -40, 20),
                sinr = RadioRules.bounded(lte.rssnr, -30, 50),
                level = RadioRules.bounded(lte.level, 0, 4),
                dbm = RadioRules.bounded(lte.dbm, -200, 0),
            )
        }
        if (nr != null) return nrSignal(nr)
        val any = list.firstOrNull() ?: return null
        return ServingSignal(
            rat = RadioRules.ratName(networkType),
            rsrp = null,
            rsrq = null,
            sinr = null,
            level = RadioRules.bounded(any.level, 0, 4),
            dbm = RadioRules.bounded(any.dbm, -200, 0),
        )
    }

    private fun nrSignal(nr: CellSignalStrengthNr) = ServingSignal(
        rat = "NR",
        rsrp = RadioRules.bounded(nr.ssRsrp, -200, 0),
        rsrq = RadioRules.bounded(nr.ssRsrq, -40, 20),
        sinr = RadioRules.bounded(nr.ssSinr, -30, 50),
        level = RadioRules.bounded(nr.level, 0, 4),
        dbm = RadioRules.bounded(nr.dbm, -200, 0),
    )

    private fun dataState(tm: TelephonyManager): String {
        val names = arrayOf("DISCONNECTED", "CONNECTING", "CONNECTED", "SUSPENDED", "DISCONNECTING", "HANDOVER_IN_PROGRESS")
        val state = runCatching { tm.dataState }.getOrDefault(-1)
        return names.getOrElse(state) { "" }
    }

    private fun serviceStateName(service: ServiceState?): String {
        val names = arrayOf("IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF")
        return service?.state?.let { names.getOrElse(it) { "" } } ?: ""
    }

    private fun network(): NetworkInfo {
        val cm = connectivity ?: return NetworkInfo.NONE
        return try {
            val active = cm.activeNetwork ?: return NetworkInfo.NONE
            val caps = cm.getNetworkCapabilities(active) ?: return NetworkInfo.NONE
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            NetworkInfo(
                transport = transport,
                connected = true,
                roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
                downstreamKbps = caps.linkDownstreamBandwidthKbps.coerceIn(0, 100_000_000),
                upstreamKbps = caps.linkUpstreamBandwidthKbps.coerceIn(0, 100_000_000),
            )
        } catch (_: SecurityException) {
            NetworkInfo.NONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun wifi(): WifiSignal {
        val wm = wifiManager ?: return WifiSignal.DISABLED
        if (!wm.isWifiEnabled) return WifiSignal.DISABLED
        maybeRequestWifiScan(wm)
        val info = try {
            wm.connectionInfo
        } catch (_: SecurityException) {
            return WifiSignal.NOT_ASSOCIATED
        } catch (_: Exception) {
            return WifiSignal.NOT_ASSOCIATED
        }
        val associated = info.networkId != -1 && info.ssid != WifiManager.UNKNOWN_SSID
        if (!associated) {
            return WifiSignal.NOT_ASSOCIATED.copy(congestion = wifiCongestion(wm, null, ""))
        }
        val freq = info.frequency.takeIf { it > 0 }
        val channel = freq?.let { wifiChannel(it) }?.takeIf { it > 0 }
        val rssi = info.rssi.takeIf { it != 0 && it != -127 }
        val link = info.linkSpeed.takeIf { it > 0 }
        val ssid = cleanWifiSsid(info.ssid)
        val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }.orEmpty()
        return WifiSignal(
            status = "associated",
            rssiDbm = rssi,
            frequencyMhz = freq,
            channel = channel,
            linkSpeedMbps = link,
            ssid = ssid,
            bssid = bssid,
            standard = wifiStandardName(info),
            congestion = wifiCongestion(wm, channel, bssid),
        )
    }

    @SuppressLint("MissingPermission")
    private fun maybeRequestWifiScan(wm: WifiManager) {
        val now = System.currentTimeMillis()
        if (now - lastWifiScanRequestMs < WIFI_SCAN_MS) return
        lastWifiScanRequestMs = now
        try {
            @Suppress("DEPRECATION")
            wm.startScan()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun wifiCongestion(wm: WifiManager, associatedChannel: Int?, associatedBssid: String): WifiCongestion? {
        val scans = try {
            wm.scanResults.orEmpty()
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (scans.isEmpty()) return WifiCongestion(score = "unknown")
        val ageMs = scans.mapNotNull { scanAgeMs(it) }.minOrNull()
        val neighbors = scans.mapNotNull { parseWifiNeighbor(it) }
            .filter { it.bssid.isEmpty() || !it.bssid.equals(associatedBssid, ignoreCase = true) }
        val bandNeighbors = if (associatedChannel != null) {
            neighbors.filter { it.channel != null && sameWifiBand(associatedChannel, it.channel) }
        } else {
            neighbors
        }
        val same = if (associatedChannel != null) {
            bandNeighbors.filter { it.channel == associatedChannel }
        } else {
            emptyList()
        }
        val adjacent = if (associatedChannel != null && associatedChannel in 1..14) {
            bandNeighbors.filter {
                val ch = it.channel ?: return@filter false
                ch != associatedChannel && kotlin.math.abs(ch - associatedChannel) == 1
            }
        } else {
            emptyList()
        }
        val strongest = same.maxOfOrNull { it.rssiDbm ?: Int.MIN_VALUE }?.takeIf { it != Int.MIN_VALUE }
        val score = when {
            same.size >= 5 || (strongest != null && strongest > -55) -> "high"
            same.size in 2..4 || (strongest != null && strongest > -70) -> "medium"
            else -> "low"
        }
        val top = bandNeighbors
            .sortedByDescending { it.rssiDbm ?: Int.MIN_VALUE }
            .take(WifiCongestion.MAX_NEIGHBORS)
        return WifiCongestion(
            scanAgeMs = ageMs,
            neighbors = bandNeighbors.size,
            sameChannel = same.size,
            adjacentChannel = adjacent.size,
            strongestNeighborRssiDbm = strongest,
            score = score,
            neighborsTop = top,
        )
    }

    private fun parseWifiNeighbor(result: ScanResult): WifiNeighbor? {
        val freq = result.frequency.takeIf { it > 0 } ?: return null
        val channel = wifiChannel(freq).takeIf { it > 0 }
        val rssi = result.level.takeIf { it != 0 && it != -127 }
        return WifiNeighbor(
            ssid = cleanWifiSsid(result.SSID),
            bssid = result.BSSID.orEmpty(),
            rssiDbm = rssi,
            frequencyMhz = freq,
            channel = channel,
            channelWidthMhz = channelWidthMhz(result),
        )
    }

    private fun scanAgeMs(result: ScanResult): Long? {
        return try {
            val elapsed = SystemClock.elapsedRealtime() - result.timestamp / 1000
            elapsed.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanWifiSsid(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim().removeSurrounding("\"")
        return when {
            trimmed == WifiManager.UNKNOWN_SSID -> ""
            trimmed.equals("<unknown ssid>", ignoreCase = true) -> ""
            else -> RadioRules.clean(trimmed, 64)
        }
    }

    private fun wifiChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2484 -> (freqMhz - 2412) / 5 + 1
        freqMhz in 5170..5825 -> (freqMhz - 5170) / 5 + 34
        freqMhz in 5955..7115 -> (freqMhz - 5955) / 5 + 1
        else -> 0
    }

    private fun sameWifiBand(a: Int, b: Int): Boolean = when {
        a in 1..14 -> b in 1..14
        a in 36..177 -> b in 36..177
        else -> a == b
    }

    private fun channelWidthMhz(result: ScanResult): Int? = when (result.channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
        else -> {
            if (Build.VERSION.SDK_INT >= 33 && result.channelWidth == ScanResult.CHANNEL_WIDTH_320MHZ) 320 else null
        }
    }

    private fun wifiStandardName(info: WifiInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return when (info.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "legacy"
            ScanResult.WIFI_STANDARD_11N -> "11n"
            ScanResult.WIFI_STANDARD_11AC -> "11ac"
            ScanResult.WIFI_STANDARD_11AX -> "11ax"
            ScanResult.WIFI_STANDARD_11AD -> "11ad"
            ScanResult.WIFI_STANDARD_11BE -> "11be"
            else -> ""
        }
    }

    /**
     * Keep GPS/network fixes warm while the sampler runs. Without this, each tick only
     * read [LocationManager.getLastKnownLocation], which can stay stale for minutes.
     */
    @SuppressLint("MissingPermission")
    private fun ensureLocationUpdates() {
        if (locationListening) return
        val lm = locations ?: return
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return
        val looper: Looper = thread.looper
        try {
            val providers = buildList {
                if (fine) add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                if (lm.allProviders.contains(FUSED_PROVIDER)) add(FUSED_PROVIDER)
            }.filter { name ->
                runCatching { lm.isProviderEnabled(name) }.getOrDefault(false)
            }
            if (providers.isEmpty()) return
            for (name in providers) {
                runCatching {
                    lm.requestLocationUpdates(name, LOCATION_INTERVAL_MS, 0f, locationListener, looper)
                }.onFailure { Log.w(TAG, "location updates $name: ${it.message}") }
            }
            // Seed immediately so the first ticks are not empty while GPS acquires.
            val seed = providers
                .mapNotNull { name -> runCatching { lm.getLastKnownLocation(name) }.getOrNull() }
                .maxByOrNull { it.time }
            if (seed != null) {
                val prev = lastFix
                if (prev == null || seed.time >= prev.time) lastFix = seed
            }
            locationListening = true
            Log.i(TAG, "location updates every ${LOCATION_INTERVAL_MS}ms on $providers")
        } catch (_: SecurityException) {
            // leave listening=false; next tick may retry after grant
        }
    }

    private fun stopLocationUpdates() {
        if (!locationListening) return
        runCatching { locations?.removeUpdates(locationListener) }
        locationListening = false
        lastFix = null
    }

    @SuppressLint("MissingPermission")
    private fun location(): LocationInfo {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return LocationInfo("permission_denied")
        val lm = locations ?: return LocationInfo("not_supported")
        return try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, FUSED_PROVIDER)
            val cached = providers
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            val best: Location = sequenceOf(lastFix).filterNotNull().plus(cached)
                .maxByOrNull { it.time }
                ?: return LocationInfo("absent")
            LocationInfo(
                status = "available",
                provider = RadioRules.clean(best.provider, 32),
                latitude = best.latitude,
                longitude = best.longitude,
                accuracyM = if (best.hasAccuracy()) best.accuracy.toDouble() else null,
                altitudeM = if (best.hasAltitude()) best.altitude else null,
                speedMps = if (best.hasSpeed()) best.speed.toDouble() else null,
                bearing = if (best.hasBearing()) best.bearing.toDouble() else null,
                tsMs = best.time,
            )
        } catch (_: SecurityException) {
            LocationInfo("permission_denied")
        }
    }

    private fun readDevice(): DeviceInfo {
        val androidId = try {
            val value = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (value != null && ANDROID_ID.matches(value)) Secret(value, "available") else Secret.ABSENT
        } catch (_: SecurityException) {
            Secret(null, "permission_denied")
        }
        return DeviceInfo(
            manufacturer = RadioRules.clean(Build.MANUFACTURER, 64),
            model = RadioRules.clean(Build.MODEL, 64),
            sdkInt = Build.VERSION.SDK_INT,
            release = RadioRules.clean(Build.VERSION.RELEASE, 32),
            androidId = androidId,
        )
    }

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }

    private class Identifiers(val imei: Secret, val imsi: Secret, val msisdn: Secret, val iccid: Secret)

    /** Per-subscription callback cache; one instance per active subscription id. */
    private inner class SubTracker(
        val subId: Int,
        @Volatile var info: SubscriptionInfo?,
        val tm: TelephonyManager,
    ) {
        @Volatile var display: TelephonyDisplayInfo? = null
        @Volatile var service: ServiceState? = null
        @Volatile var signal: SignalStrength? = null
        @Volatile var physical: List<PhysicalChannelConfig>? = null
        @Volatile private var cellCache: List<CellInfo>? = null
        @Volatile private var cellCacheMs = 0L
        @Volatile private var cellRequestMs = 0L
        @Volatile private var cellRequestOpen = false
        private var ids: Identifiers? = null
        private val callbacks = ArrayList<TelephonyCallback>()
        private var physicalRegistered = false

        val slot: Int get() = info?.simSlotIndex ?: 0

        fun register() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            registerCallback(StateCallback(this))
            ensurePhysical()
        }

        /** PhysicalChannelConfig needs READ_PRECISE_PHONE_STATE, which may be granted after start. */
        fun ensurePhysical() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            if (physicalRegistered || !hasPrecisePermission()) return
            physicalRegistered = registerCallback(PhysicalCallback(this))
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun registerCallback(callback: TelephonyCallback): Boolean = try {
            tm.registerTelephonyCallback(executor, callback)
            callbacks += callback
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

        fun unregister() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            callbacks.forEach { runCatching { tm.unregisterTelephonyCallback(it) } }
            callbacks.clear()
            physicalRegistered = false
        }

        fun carriers(): List<ComponentCarrier> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
            return physical.orEmpty().map { carrier(it) }
        }

        /**
         * Modem refresh in parallel with this tick. [cellInfos] does not wait for it;
         * the answer is merged on the next ticks for [CellMerge.HOLD_MS].
         */
        @SuppressLint("MissingPermission")
        fun requestCellUpdate() {
            val now = System.currentTimeMillis()
            if (cellRequestOpen || now - cellRequestMs < CELL_REQUEST_MS) return
            cellRequestMs = now
            cellRequestOpen = true
            try {
                tm.requestCellInfoUpdate(
                    executor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            cellCache = cellInfo.toList()
                            cellCacheMs = System.currentTimeMillis()
                            cellRequestOpen = false
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            cellRequestOpen = false
                        }
                    },
                )
            } catch (_: SecurityException) {
                cellRequestOpen = false
            } catch (_: IllegalStateException) {
                cellRequestOpen = false
            }
        }

        /** Live cache plus the latest parallel modem answer. Neither list replaces the other. */
        @SuppressLint("MissingPermission")
        fun cellInfos(): List<CellInfo> {
            val live = try {
                tm.allCellInfo.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
            val age = System.currentTimeMillis() - cellCacheMs
            val fresh = cellCache?.takeIf { age in 0..CellMerge.HOLD_MS }
            return mergeCellInfos(live, fresh)
        }

        /** Restricted identifiers are probed once; OEM getprop can still expose IMEI on Xiaomi. */
        @SuppressLint("MissingPermission", "HardwareIds")
        fun identifiers(): Identifiers {
            ids?.let { return it }
            val slot = info?.simSlotIndex ?: 0
            val created = Identifiers(
                imei = resolveImei(slot),
                imsi = identifier { tm.subscriberId },
                msisdn = identifier { tm.line1Number },
                iccid = identifier { tm.simSerialNumber },
            )
            ids = created
            return created
        }

        private fun resolveImei(slot: Int): Secret {
            val primary = identifier { tm.imei }
            if (primary.status == "available") return primary
            val oem = oemImei(slot) ?: return primary
            if (loggedMiss.add("imei-oem-$slot")) Log.i(TAG, "imei slot=$slot via oem prop")
            return Secret(oem, "available")
        }

        private fun identifier(read: () -> String?): Secret = try {
            val text = RadioRules.clean(read(), 32)
            if (DIGIT_ID.matches(text)) Secret(text, "available") else Secret.ABSENT
        } catch (e: SecurityException) {
            val message = e.message.orEmpty()
            if (message.contains("PRIVILEGED") || message.contains("carrier") || message.contains("identifier")) {
                Secret(null, "platform_restricted")
            } else {
                Secret(null, "permission_denied")
            }
        } catch (_: Exception) {
            Secret(null, "not_supported")
        }
    }

    /** Xiaomi and some OEMs publish slot IMEIs as readable system properties. */
    private fun oemImei(slot: Int): String? {
        exemptHiddenApi()
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/os/SystemProperties;")
        } catch (_: Throwable) {
        }
        val names = when (slot) {
            0 -> listOf("ro.ril.miui.imei0", "ro.ril.oem.imei", "ro.ril.oem.imei1")
            1 -> listOf("ro.ril.miui.imei1", "ro.ril.oem.imei2")
            else -> listOf("ro.ril.miui.imei$slot", "ro.ril.oem.imei${slot + 1}")
        }
        for (name in names) {
            val value = systemProp(name) ?: getprop(name) ?: continue
            val cleaned = RadioRules.clean(value, 32)
            if (DIGIT_ID.matches(cleaned) && cleaned.length in 14..16) {
                if (loggedMiss.add("imei-prop-$name")) Log.i(TAG, "imei prop $name len=${cleaned.length}")
                return cleaned
            }
        }
        if (loggedMiss.add("imei-oem-miss-$slot")) Log.i(TAG, "imei oem miss slot=$slot")
        return null
    }

    private fun systemProp(name: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = try {
                clazz.getMethod("get", String::class.java, String::class.java)
            } catch (_: Exception) {
                null
            }
            val viaMethod = method?.invoke(null, name, "") as? String
            if (!viaMethod.isNullOrBlank()) return viaMethod.trim()
            val viaBypass = HiddenApiBypass.invoke(clazz, null, "get", name, "") as? String
            viaBypass?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getprop(name: String): String? = try {
        val process = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        text.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class StateCallback(private val tracker: SubTracker) :
        TelephonyCallback(),
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.SignalStrengthsListener {
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            tracker.display = telephonyDisplayInfo
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            tracker.service = serviceState
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            tracker.signal = signalStrength
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class PhysicalCallback(private val tracker: SubTracker) :
        TelephonyCallback(),
        TelephonyCallback.PhysicalChannelConfigListener {
        override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
            tracker.physical = configs.toList()
        }
    }

    companion object {
        private const val TAG = "MySignalNr"
        private const val NO_SUB = -1
        private val exempted = AtomicBoolean(false)
        private val loggedMiss = CopyOnWriteArraySet<String>()
        private const val CELL_REQUEST_MS = 2_500L
        private const val WIFI_SCAN_MS = 30_000L
        private const val APN_CACHE_MS = 30_000L
        /** Match [RadioService.INTERVAL_MS] so fixes stay fresh for each measure tick. */
        private const val LOCATION_INTERVAL_MS = 3_000L
        private const val FUSED_PROVIDER = "fused"
        private val ANDROID_ID = Regex("[0-9a-fA-F]{8,32}")
        private val DIGIT_ID = Regex("""\+?[0-9]{6,20}""")

        @RequiresApi(Build.VERSION_CODES.S)
        private fun carrier(config: PhysicalChannelConfig): ComponentCarrier = ComponentCarrier(
            rat = RadioRules.ratName(config.networkType),
            status = when (config.connectionStatus) {
                PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING -> ComponentCarrier.STATUS_PRIMARY
                PhysicalChannelConfig.CONNECTION_SECONDARY_SERVING -> ComponentCarrier.STATUS_SECONDARY
                else -> ComponentCarrier.STATUS_UNKNOWN
            },
            channel = RadioRules.bounded(config.downlinkChannelNumber, 0, 3_279_165),
            band = RadioRules.bounded(config.band, 1, 300),
            bandwidthKhz = RadioRules.bounded(config.cellBandwidthDownlinkKhz, 1, 400_000),
            pci = RadioRules.bounded(config.physicalCellId, 0, 1007),
        )
    }
}

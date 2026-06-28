package com.telcoagent.udpclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

class CellInfoCollector(private val context: Context) {
    private val telephonyManager: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Volatile
    private var cachedCells: List<CellInfo>? = null

    private val cachedDeviceInfo by lazy { DeviceInfoReader.read(context, telephonyManager) }

    fun hasPermission(): Boolean {
        return hasLocationPermission() && hasPhoneStatePermission()
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPhoneStatePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PRECISE_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPrecisePhoneStatePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return hasPhoneStatePermission()
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PRECISE_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun requestFreshCells(executor: Executor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val tm = telephonyManager ?: return
        tm.requestCellInfoUpdate(
            executor,
            object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cells: MutableList<CellInfo>) {
                    cachedCells = cells
                }

                override fun onError(errorCode: Int, detail: Throwable?) = Unit
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun collect(): CellInfoSnapshot {
        if (!hasLocationPermission()) {
            return CellInfoSnapshot(error = "Location permission required")
        }
        if (!hasPhoneStatePermission()) {
            return CellInfoSnapshot(error = "Phone state permission required for NR/5G")
        }

        val tm = telephonyManager ?: return CellInfoSnapshot(error = "Telephony unavailable")
        val cells = try {
            mergeCells(tm.allCellInfo ?: emptyList(), cachedCells ?: emptyList())
                .filter { it !is android.telephony.CellInfoGsm }
        } catch (_: SecurityException) {
            return CellInfoSnapshot(error = "Permission denied")
        }

        if (cells.isEmpty()) {
            return CellInfoSnapshot(error = "No cell info available")
        }

        val updatedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val registered = cells.filter { it.isRegistered }
        val lte = pickBest(cells, registered) { it is CellInfoLte } as? CellInfoLte
        val nrCell = cells.filterIsInstance<CellInfoNr>().maxByOrNull { signalLevel(it) }
        val nrSignal = findNrSignalStrength(tm)

        val snapshot = when {
            lte != null -> {
                val primary = parseLte(lte, updatedAt)
                val dcNr = resolveDcNr(nrCell, nrSignal)
                if (dcNr != null) {
                    primary.copy(tech = "LTE+NR", dcNr = dcNr)
                } else {
                    primary
                }
            }
            nrCell != null -> parseNr(nrCell, updatedAt, nrSignal)
            else -> {
                val selected = selectBestCell(cells)
                    ?: return attachMetadata(CellInfoSnapshot(error = "No registered cell"))
                when (selected) {
                    is CellInfoWcdma -> parseWcdma(selected, updatedAt)
                    else -> CellInfoSnapshot(tech = selected.javaClass.simpleName, updatedAt = updatedAt)
                }
            }
        }
        return attachMetadata(snapshot)
    }

    private fun attachMetadata(snapshot: CellInfoSnapshot): CellInfoSnapshot {
        val (lat, lon) = LocationReader.read(context)
        val info = cachedDeviceInfo
        return snapshot.copy(
            latitude = lat,
            longitude = lon,
            imei = info.imei,
            imsi = info.imsi,
            deviceId = info.deviceId,
            operator = ProviderResolver.resolveOperator(context, info.operator),
        )
    }

    private fun mergeCells(live: List<CellInfo>, cached: List<CellInfo>): List<CellInfo> {
        if (cached.isEmpty()) return live
        if (live.isEmpty()) return cached
        return (cached + live).distinctBy { cellKey(it) }
    }

    private fun cellKey(cell: CellInfo): String {
        return when (cell) {
            is CellInfoLte -> {
                val id = cell.cellIdentity
                "lte:${id.ci}:${id.earfcn}:${cell.isRegistered}"
            }
            is CellInfoNr -> {
                val id = cell.cellIdentity as? CellIdentityNr
                "nr:${id?.nci}:${id?.nrarfcn}:${id?.pci}:${cell.isRegistered}"
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                "wcdma:${id.cid}:${id.uarfcn}:${cell.isRegistered}"
            }
            else -> "${cell.javaClass.name}:${cell.isRegistered}:${cell.hashCode()}"
        }
    }

    private fun resolveDcNr(
        nrCell: CellInfoNr?,
        nrSignal: CellSignalStrengthNr?,
    ): NrCellInfo? {
        val fromCell = nrCell?.let { parseDcNr(it, nrSignal) }
        val fromSignal = nrSignal?.let { parseDcNrFromSignal(it) }

        return when {
            fromCell != null && fromCell.hasIdentity() -> {
                mergeNrInfo(fromCell, fromSignal)
            }
            fromCell != null && fromCell.hasSignal() -> fromCell
            fromSignal != null && fromSignal.hasSignal() -> fromSignal
            else -> fromCell?.takeIf { it.hasIdentity() }
        }
    }

    private fun mergeNrInfo(primary: NrCellInfo, signalFallback: NrCellInfo?): NrCellInfo {
        if (signalFallback == null || primary.hasSignal()) return primary
        return primary.copy(
            rsrp = signalFallback.rsrp,
            rsrq = signalFallback.rsrq,
            snr = signalFallback.snr,
        )
    }

    @SuppressLint("MissingPermission")
    private fun findNrSignalStrength(tm: TelephonyManager): CellSignalStrengthNr? {
        return tm.signalStrength?.cellSignalStrengths
            ?.filterIsInstance<CellSignalStrengthNr>()
            ?.maxByOrNull { it.level }
    }

    private fun pickBest(
        cells: List<CellInfo>,
        registered: List<CellInfo>,
        predicate: (CellInfo) -> Boolean,
    ): CellInfo? {
        val registeredMatch = registered.filter(predicate)
        val pool = if (registeredMatch.isNotEmpty()) registeredMatch else cells.filter(predicate)
        return pool.maxByOrNull { signalLevel(it) }
    }

    private fun selectBestCell(cells: List<CellInfo>): CellInfo? {
        val registered = cells.filter { it.isRegistered }
        val pool = if (registered.isNotEmpty()) registered else cells
        return pool.maxByOrNull { signalLevel(it) }
    }

    private fun signalLevel(cell: CellInfo): Int {
        return when (cell) {
            is CellInfoLte -> cell.cellSignalStrength.level
            is CellInfoNr -> cell.cellSignalStrength.level
            is CellInfoWcdma -> cell.cellSignalStrength.level
            else -> 0
        }
    }

    private fun parseLte(cell: CellInfoLte, updatedAt: String): CellInfoSnapshot {
        val identity = cell.cellIdentity
        val signal = cell.cellSignalStrength
        val ci = identity.ci
        val enb = CellIdentityMask.formatLteEnb(ci)
        val sector = CellIdentityMask.formatLteCellId(ci)
        val rsrp = signal.rsrp

        return CellInfoSnapshot(
            tech = "LTE",
            tac = CellIdentityMask.formatInt(identity.tac),
            enb = enb,
            cellId = sector,
            arfcn = CellIdentityMask.formatInt(identity.earfcn),
            band = RadioBandMapper.lteBandLabel(identity.earfcn),
            rsrp = formatDbm(rsrp),
            rsrq = formatDb(signal.rsrq),
            snr = formatSnr(signal.rssnr),
            rsrpDbm = if (rsrp != CellInfo.UNAVAILABLE) rsrp else null,
            updatedAt = updatedAt,
        )
    }

    private fun parseNr(
        cell: CellInfoNr,
        updatedAt: String,
        fallbackSignal: CellSignalStrengthNr?,
    ): CellInfoSnapshot {
        val identity = cell.cellIdentity as? CellIdentityNr
            ?: return CellInfoSnapshot(tech = "NR", updatedAt = updatedAt)
        val cellSignal = cell.cellSignalStrength as? CellSignalStrengthNr
        val signal = pickNrSignal(cellSignal, fallbackSignal)
            ?: return CellInfoSnapshot(tech = "NR", updatedAt = updatedAt)
        val nci = identity.nci

        return CellInfoSnapshot(
            tech = "NR",
            tac = CellIdentityMask.formatInt(identity.tac),
            enb = CellIdentityMask.formatGnb(nci),
            cellId = CellIdentityMask.formatNrCellId(nci),
            arfcn = CellIdentityMask.formatInt(identity.nrarfcn),
            band = RadioBandMapper.nrBandLabel(identity.nrarfcn),
            rsrp = formatDbm(nrRsrp(signal)),
            rsrq = formatDb(nrRsrq(signal)),
            snr = formatSnr(nrSinr(signal)),
            rsrpDbm = nrRsrp(signal).takeIf { it != CellInfo.UNAVAILABLE },
            updatedAt = updatedAt,
        )
    }

    private fun parseDcNr(
        cell: CellInfoNr,
        fallbackSignal: CellSignalStrengthNr?,
    ): NrCellInfo {
        val identity = cell.cellIdentity as? CellIdentityNr
        val cellSignal = cell.cellSignalStrength as? CellSignalStrengthNr
        val signal = pickNrSignal(cellSignal, fallbackSignal) ?: return NrCellInfo()
        val nci = identity?.nci ?: CellInfo.UNAVAILABLE.toLong()

        return NrCellInfo(
            pci = identity?.let { CellIdentityMask.formatPci(it.pci) } ?: "—",
            gnb = CellIdentityMask.formatGnb(nci),
            cellId = CellIdentityMask.formatNrCellId(nci),
            arfcn = identity?.let { CellIdentityMask.formatInt(it.nrarfcn) } ?: "—",
            band = identity?.let { RadioBandMapper.nrBandLabel(it.nrarfcn) } ?: "—",
            rsrp = formatDbm(nrRsrp(signal)),
            rsrq = formatDb(nrRsrq(signal)),
            snr = formatSnr(nrSinr(signal)),
        )
    }

    private fun parseDcNrFromSignal(signal: CellSignalStrengthNr): NrCellInfo {
        return NrCellInfo(
            rsrp = formatDbm(nrRsrp(signal)),
            rsrq = formatDb(nrRsrq(signal)),
            snr = formatSnr(nrSinr(signal)),
        )
    }

    private fun pickNrSignal(
        primary: CellSignalStrengthNr?,
        fallback: CellSignalStrengthNr?,
    ): CellSignalStrengthNr? {
        return when {
            primary != null && hasValidNrSignal(primary) -> primary
            fallback != null && hasValidNrSignal(fallback) -> fallback
            else -> primary ?: fallback
        }
    }

    private fun hasValidNrSignal(signal: CellSignalStrengthNr): Boolean {
        return nrRsrp(signal) != CellInfo.UNAVAILABLE ||
            nrRsrq(signal) != CellInfo.UNAVAILABLE ||
            nrSinr(signal) != CellInfo.UNAVAILABLE
    }

    private fun nrRsrp(signal: CellSignalStrengthNr): Int {
        if (signal.ssRsrp != CellInfo.UNAVAILABLE) return signal.ssRsrp
        if (signal.csiRsrp != CellInfo.UNAVAILABLE) return signal.csiRsrp
        return CellInfo.UNAVAILABLE
    }

    private fun nrRsrq(signal: CellSignalStrengthNr): Int {
        if (signal.ssRsrq != CellInfo.UNAVAILABLE) return signal.ssRsrq
        if (signal.csiRsrq != CellInfo.UNAVAILABLE) return signal.csiRsrq
        return CellInfo.UNAVAILABLE
    }

    private fun nrSinr(signal: CellSignalStrengthNr): Int {
        if (signal.ssSinr != CellInfo.UNAVAILABLE) return signal.ssSinr
        if (signal.csiSinr != CellInfo.UNAVAILABLE) return signal.csiSinr
        return CellInfo.UNAVAILABLE
    }

    private fun parseWcdma(cell: CellInfoWcdma, updatedAt: String): CellInfoSnapshot {
        val identity = cell.cellIdentity
        val signal = cell.cellSignalStrength

        return CellInfoSnapshot(
            tech = "WCDMA",
            tac = CellIdentityMask.formatInt(identity.lac),
            enb = "—",
            cellId = CellIdentityMask.formatInt(identity.cid),
            arfcn = CellIdentityMask.formatInt(identity.uarfcn),
            rsrp = "—",
            rsrq = "—",
            snr = "—",
            rsrpDbm = if (signal.dbm != CellInfo.UNAVAILABLE) signal.dbm else null,
            updatedAt = updatedAt,
        )
    }

    private fun formatDbm(value: Int): String {
        return if (value == CellInfo.UNAVAILABLE) "—" else value.toString()
    }

    private fun formatDb(value: Int): String {
        return if (value == CellInfo.UNAVAILABLE) "—" else value.toString()
    }

    private fun formatSnr(value: Int): String {
        return if (value == CellInfo.UNAVAILABLE) {
            "—"
        } else {
            "%.1f".format(value / 10.0)
        }
    }
}

fun NrCellInfo.hasSignal(): Boolean {
    return rsrp != "—" || rsrq != "—" || snr != "—"
}

fun NrCellInfo.hasIdentity(): Boolean {
    return pci != "—" || gnb != "—" || cellId != "—" || arfcn != "—"
}

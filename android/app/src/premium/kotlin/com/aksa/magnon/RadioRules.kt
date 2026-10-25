package com.aksa.magnon

/**
 * Pure NR / CA derivation rules shared by the sampler and unit tests.
 *
 * nrState follows ServiceState/NetworkRegistrationInfo: 0 NONE, 1 RESTRICTED,
 * 2 NOT_RESTRICTED (EN-DC advertised), 3 CONNECTED (NR secondary cell group).
 * The value is hidden API, so it is read from the PS-domain registration text,
 * which is the same text `dumpsys telephony.registry` prints.
 */
object RadioRules {
    const val NETWORK_TYPE_LTE = 13
    const val NETWORK_TYPE_LTE_CA = 19
    const val NETWORK_TYPE_NR = 20
    const val NR_STATE_CONNECTED = 3
    const val OVERRIDE_LTE_CA = 1

    private val NR_STATE_NAMES = listOf(
        "NOT_RESTRICTED" to 2,
        "RESTRICTED" to 1,
        "CONNECTED" to 3,
        "NONE" to 0,
    )
    private val FREQ_NAMES = listOf(
        "MMWAVE" to 4,
        "HIGH" to 3,
        "MID" to 2,
        "LOW" to 1,
        "UNKNOWN" to 0,
    )
    private val CA_TRUE = Regex("""(?i)isUsingCarrierAggregation\s*=\s*true""")
    private val CA_FIELD = Regex("""(?i)isUsingCarrierAggregation\s*=""")
    private val ENDC_TRUE = Regex("""(?i)isEnDcAvailable\s*=\s*true""")
    private val ENDC_FIELD = Regex("""(?i)isEnDcAvailable\s*=""")
    private val CELL_BANDWIDTHS = Regex("""mCellBandwidths=\[([0-9,\s]*)]""")
    private val CHANNEL_NUMBER = Regex("""mChannelNumber=(-?\d+)""")

    /** Value after `nrState=`; names are matched longest-first so RESTRICTED never eats NOT_RESTRICTED. */
    fun parseNrState(text: String?): Int? = parseNamed(text, "nrState=", NR_STATE_NAMES, 0..3)

    fun parseNrFrequencyRange(text: String?): Int? =
        parseNamed(text, "nrFrequencyRange=", FREQ_NAMES, 0..4)

    /** True / false when the registration text carries the flag, null when it is absent. */
    fun parseCarrierAggregation(text: String?): Boolean? {
        if (text.isNullOrEmpty() || !CA_FIELD.containsMatchIn(text)) return null
        return CA_TRUE.containsMatchIn(text)
    }

    /** Serving EARFCN printed in ServiceState, the same field dumpsys shows as mChannelNumber. */
    fun parseChannelNumber(text: String?): Int? {
        if (text.isNullOrEmpty()) return null
        val match = CHANNEL_NUMBER.find(text) ?: return null
        return bounded(match.groupValues[1].toIntOrNull() ?: return null, 1, 10_000_000)
    }

    /** Every ServiceState cell bandwidth, in order. Includes LTE and NR component carriers. */
    fun cellBandwidthsKhz(text: String?): List<Int> {
        if (text.isNullOrEmpty()) return emptyList()
        val match = CELL_BANDWIDTHS.find(text) ?: return emptyList()
        return match.groupValues[1].split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..400_000 }
    }

    fun khzLabel(khz: List<Int>): String =
        if (khz.isEmpty()) "" else khz.joinToString("+") + " kHz"

    fun mhzLabel(khz: List<Int>): String =
        khz.joinToString("+") { value ->
            if (value % 1000 == 0) (value / 1000).toString() else "%.1f".format(value / 1000.0)
        } + " MHz"

    fun parseEndcAvailable(text: String?): Boolean? {
        if (text.isNullOrEmpty() || !ENDC_FIELD.containsMatchIn(text)) return null
        return ENDC_TRUE.containsMatchIn(text)
    }

    private fun parseNamed(
        text: String?,
        prefix: String,
        names: List<Pair<String, Int>>,
        range: IntRange,
    ): Int? {
        if (text.isNullOrEmpty()) return null
        val at = text.indexOf(prefix)
        if (at < 0) return null
        val tail = text.substring(at + prefix.length)
        names.firstOrNull { tail.startsWith(it.first) }?.let { return it.second }
        val digits = tail.takeWhile { it.isDigit() }
        val value = digits.toIntOrNull() ?: return null
        return value.takeIf { it in range }
    }

    /** Result plus where it came from, so the UI can show which signal decided it. */
    data class Derived<T>(val value: T?, val source: String)

    /**
     * OpenSignal order: the hidden getNrState() integer, then the printed name, then an NR
     * secondary carrier. A redacted `nrState=****` string does not count as a value.
     */
    fun selectNrState(
        reflected: Int?,
        parsed: Int?,
        carriers: List<ComponentCarrier>,
        networkType: Int,
    ): Derived<Int> {
        val raw = reflected?.takeIf { it in 0..3 } ?: parsed
        val nrSecondary = carriers.any { it.rat == "NR" && it.isSecondary }
        val lteAnchor = carriers.any { it.rat == "LTE" && it.isPrimary }
        if (nrSecondary && lteAnchor && (raw == null || raw < NR_STATE_CONNECTED)) {
            return Derived(NR_STATE_CONNECTED, "physical_channel")
        }
        if (reflected != null && reflected in 0..3) return Derived(reflected, "reflection")
        if (parsed != null) return Derived(parsed, "text")
        if (networkType == NETWORK_TYPE_NR) return Derived(NR_STATE_CONNECTED, "sa")
        return Derived(null, "unavailable")
    }

    /**
     * OpenSignal treats the ServiceState text as CA when it contains the true flag anywhere.
     * The nested PS registration can say false while the service-level flag says true.
     */
    fun deriveCarrierAggregation(
        networkType: Int,
        overrideNetworkType: Int?,
        serviceState: Boolean?,
        registration: Boolean?,
        carriers: List<ComponentCarrier>,
    ): Derived<Boolean> {
        if (networkType == NETWORK_TYPE_LTE_CA) return Derived(true, "network_type")
        if (serviceState == true) return Derived(true, "service_state")
        val primary = carriers.firstOrNull { it.isPrimary }
        if (primary != null && carriers.any { it.isSecondary && it.rat == primary.rat }) {
            return Derived(true, "physical_channel")
        }
        if (registration == true) return Derived(true, "registration")
        if (overrideNetworkType == OVERRIDE_LTE_CA) return Derived(true, "display")
        if (serviceState == false || registration == false || carriers.isNotEmpty()) {
            return Derived(false, "service_state")
        }
        return Derived(null, "unavailable")
    }

    /** ServiceState frequency range from an NR-ARFCN (3GPP 38.104 global raster). */
    fun nrFrequencyRangeFromArfcn(nrarfcn: Int?): Int? {
        if (nrarfcn == null || nrarfcn < 0 || nrarfcn > 3_279_165) return null
        val mhz = when {
            nrarfcn < 600_000 -> nrarfcn * 0.005
            nrarfcn < 2_016_667 -> 3000.0 + 0.015 * (nrarfcn - 600_000)
            else -> 24250.08 + 0.06 * (nrarfcn - 2_016_667)
        }
        return when {
            mhz < 1000.0 -> 1
            mhz < 3000.0 -> 2
            mhz < 6000.0 -> 3
            else -> 4
        }
    }

    fun ratName(networkType: Int): String = when (networkType) {
        NETWORK_TYPE_LTE, NETWORK_TYPE_LTE_CA -> "LTE"
        NETWORK_TYPE_NR -> "NR"
        3, 8, 9, 10, 15 -> "WCDMA"
        4, 5, 6, 7, 12, 14 -> "CDMA"
        17 -> "TDSCDMA"
        1, 2, 11, 16 -> "GSM"
        else -> "UNKNOWN"
    }

    /**
     * CellInfo on some Qualcomm phones leaves rssnr unavailable while
     * SignalStrength still has the camped cell's SINR. Copy that value onto
     * the first registered cell of each SIM and RAT, and leave neighbours alone.
     */
    fun fillServingSinr(cells: List<RadioCell>, sinrBySubRat: Map<Pair<Int?, String>, Int>): List<RadioCell> {
        if (sinrBySubRat.isEmpty()) return cells
        val used = HashSet<Pair<Int?, String>>()
        return cells.map { cell ->
            if (cell.sinr != null || !cell.registered) return@map cell
            val key = cell.subId to cell.rat
            val sinr = sinrBySubRat[key] ?: return@map cell
            if (!used.add(key)) return@map cell
            if (cell.rat == "NR") cell.copy(sinr = sinr, ssSinr = cell.ssSinr ?: sinr) else cell.copy(sinr = sinr)
        }
    }

    /** Reject the platform's UNAVAILABLE sentinels and out-of-range values. */
    fun bounded(value: Int, low: Int, high: Int): Int? {
        if (value == Int.MAX_VALUE || value == Int.MIN_VALUE) return null
        return value.takeIf { it in low..high }
    }

    fun cellId(value: Long): String {
        if (value < 0 || value == Long.MAX_VALUE || value == Int.MAX_VALUE.toLong()) return ""
        return value.toString()
    }

    fun clean(value: CharSequence?, limit: Int): String {
        if (value == null) return ""
        val text = value.toString().filter { it.code >= 32 && it.code != 127 }.trim()
        if (text == "null") return ""
        return if (text.length <= limit) text else text.substring(0, limit)
    }
}

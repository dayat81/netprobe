package com.aksa.mysignal

/**
 * Home-widget facing 5G path status.
 *
 * - advertised_only — display override shows 5G/NR_NSA but nrState ≠ CONNECTED
 * - data_path — NR secondary (NSA CONNECTED) or NR SA on the data plane
 * - lte_only / unknown — not in a 5G advertised/connected state
 */
enum class NrPathStatus(val wire: String, val title: String) {
    ADVERTISED_ONLY("advertised_only", "advertised_only"),
    DATA_PATH("data_path", "data_path"),
    LTE_ONLY("lte_only", "lte_only"),
    UNKNOWN("unknown", "unknown"),
    ;

    companion object {
        fun from(verdict: NrVerdictLabel): NrPathStatus = when (verdict) {
            NrVerdictLabel.NSA_ADVERTISED -> ADVERTISED_ONLY
            NrVerdictLabel.NSA_CONNECTED, NrVerdictLabel.NR_SA -> DATA_PATH
            NrVerdictLabel.LTE_ONLY -> LTE_ONLY
            NrVerdictLabel.UNKNOWN -> UNKNOWN
        }
    }
}

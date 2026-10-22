package com.aksa.mysignal

/**
 * Explicit verdict rules mirroring OpenSignal display vs ServiceState NR layers.
 *
 * - LTE_ONLY — override NONE/LTE_CA and nrState ≠ CONNECTED
 * - NSA_ADVERTISED — override NR_NSA but nrState ≠ CONNECTED (5G icon, data path not yet)
 * - NSA_CONNECTED — nrState == CONNECTED (usually with NR_NSA override)
 * - NR_SA — dataNetworkType == NR
 */
enum class NrVerdictLabel {
    LTE_ONLY,
    NSA_ADVERTISED,
    NSA_CONNECTED,
    NR_SA,
    UNKNOWN,
}

data class NrVerdict(
    val label: NrVerdictLabel,
    val summary: String,
    val detail: String,
) {
    companion object {
        fun evaluate(
            overrideNetworkType: Int?,
            nrState: Int?,
            dataNetworkType: Int?,
        ): NrVerdict {
            val override = overrideNetworkType ?: 0
            val nr = nrState ?: 0
            val data = dataNetworkType ?: 0

            if (data == NrLabels.NETWORK_TYPE_NR) {
                return NrVerdict(
                    label = NrVerdictLabel.NR_SA,
                    summary = "NR_SA",
                    detail = "dataNetworkType=NR — standalone NR data path",
                )
            }

            if (nr == NrLabels.NR_STATE_CONNECTED) {
                return NrVerdict(
                    label = NrVerdictLabel.NSA_CONNECTED,
                    summary = "NSA_CONNECTED",
                    detail = "nrState=CONNECTED — NR secondary cell active on data path" +
                        " (override=${NrLabels.override(override)})",
                )
            }

            if (override == NrLabels.OVERRIDE_NR_NSA ||
                override == NrLabels.OVERRIDE_NR_NSA_MMWAVE ||
                override == NrLabels.OVERRIDE_NR_ADVANCED
            ) {
                return NrVerdict(
                    label = NrVerdictLabel.NSA_ADVERTISED,
                    summary = "NSA_ADVERTISED",
                    detail = "override=${NrLabels.override(override)} but nrState=" +
                        "${NrLabels.nrState(nr)} — 5G icon advertised, NR not CONNECTED",
                )
            }

            if (data == NrLabels.NETWORK_TYPE_LTE || data == 0 ||
                override == 0 || override == 1 || override == 2
            ) {
                return NrVerdict(
                    label = NrVerdictLabel.LTE_ONLY,
                    summary = "LTE_ONLY",
                    detail = "override=${NrLabels.override(override)}, nrState=${NrLabels.nrState(nr)}, " +
                        "data=${NrLabels.networkType(data)}",
                )
            }

            return NrVerdict(
                label = NrVerdictLabel.UNKNOWN,
                summary = "UNKNOWN",
                detail = "override=${NrLabels.override(override)}, nrState=${NrLabels.nrState(nr)}, " +
                    "data=${NrLabels.networkType(data)}",
            )
        }
    }
}

package com.aksa.mysignal

/** Row text for ARFCN and eNB values, with the RAT next to the number. */
object RadioChartLabels {
    /** Chart identity. Slot is 0-based so two SIMs on one PLMN stay apart. */
    fun slotId(plmn: String, slot: Int): String =
        if (plmn.isEmpty() || slot < 0) plmn else "$plmn@$slot"

    fun plmnOf(id: String): String = id.substringBefore('@')

    /** 1-based slot, matching the SIM slot shown in Android settings. */
    fun slotLabel(id: String): String {
        val plmn = plmnOf(id)
        val slot = id.substringAfter('@', "").toIntOrNull()
        return if (slot == null || slot < 0) plmn else "$plmn ·${slot + 1}"
    }

    fun carrierKey(plmn: String, rat: String, channel: Int): String = "$plmn $rat $channel"

    fun carrierLabel(key: String, showPlmn: Boolean): String {
        val parts = key.split(' ')
        if (parts.size < 3) return key
        val plmn = parts[0]
        val rat = parts[1]
        val channel = parts[2]
        return if (showPlmn) "$channel $rat ${slotLabel(plmn)}" else "$channel $rat"
    }

    fun siteKey(rat: String, ci: Long, plmn: String): String = "$rat|${ci / 256}|$plmn"

    fun siteLabel(key: String, showPlmn: Boolean): String {
        val parts = key.split('|')
        if (parts.size < 3) return key
        val rat = parts[0]
        val id = parts[1]
        val plmn = parts[2]
        return if (showPlmn) "$id $rat ${slotLabel(plmn)}" else "$id $rat"
    }

    fun plmnOfCarrier(key: String): String = key.substringBefore(' ')

    fun plmnOfSite(key: String): String = key.substringAfterLast('|')
}

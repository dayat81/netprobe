package com.aksa.mysignal

/**
 * Picks one CellInfo per cell when the live cache and a parallel modem refresh disagree.
 * A row with an ARFCN beats one without. A serving row beats a neighbor. A fresh modem
 * answer beats the cache when those are equal.
 */
object CellMerge {
    const val HOLD_MS = 8_000L

    data class Item(
        val id: String,
        val channel: Int?,
        val serving: Boolean,
        val fresh: Boolean,
    )

    fun wins(candidate: Item, current: Item): Boolean {
        val candidateChannel = candidate.channel != null
        val currentChannel = current.channel != null
        if (candidateChannel != currentChannel) return candidateChannel
        if (candidate.serving != current.serving) return candidate.serving
        return candidate.fresh && !current.fresh
    }
}

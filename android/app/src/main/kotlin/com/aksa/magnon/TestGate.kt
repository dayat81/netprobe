package com.aksa.magnon

/** A round may start only when the provider and a GPS place name are already known. */
object TestGate {
    fun ready(
        provider: String,
        kelurahan: String,
        kecamatan: String,
        kabupaten: String,
        provinsi: String,
    ): Boolean {
        val name = provider.trim()
        if (name.isEmpty() || name == "—" || name == "…" || name == "-") return false
        return PlaceFill.named(kelurahan, kecamatan, kabupaten, provinsi)
    }
}

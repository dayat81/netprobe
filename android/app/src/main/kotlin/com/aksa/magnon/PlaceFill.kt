package com.aksa.magnon

/** Whether a saved test still needs a place name. Coordinates are not shown. */
object PlaceFill {
    fun named(kelurahan: String, kecamatan: String, kabupaten: String, provinsi: String): Boolean =
        listOf(kelurahan, kecamatan, kabupaten, provinsi).any { it.isNotBlank() }

    fun pending(
        kelurahan: String,
        kecamatan: String,
        kabupaten: String,
        provinsi: String,
        latitude: Double?,
        longitude: Double?,
    ): Boolean {
        if (named(kelurahan, kecamatan, kabupaten, provinsi)) return false
        return latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()
    }

    fun label(kelurahan: String, kecamatan: String, kabupaten: String, provinsi: String): String =
        listOf(kelurahan, kecamatan, kabupaten, provinsi)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
            .ifBlank { "—" }
}

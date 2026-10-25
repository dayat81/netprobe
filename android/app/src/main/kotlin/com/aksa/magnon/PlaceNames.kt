package com.aksa.magnon

import java.util.Locale

/** Administrative names from a reverse-geocode line. Coordinates are not kept. */
object PlaceNames {
    data class Names(
        val kelurahan: String = "",
        val kecamatan: String = "",
        val kabupaten: String = "",
        val provinsi: String = "",
    )

    private val kelurahanLine = Regex("""(?i)^(?:kelurahan|kel\.?|desa)\s+(.+)$""")
    private val kecamatanLine = Regex("""(?i)^(?:kecamatan|kec\.?)\s+(.+)$""")
    private val kabupatenLine = Regex("""(?i)^(?:kota|kabupaten|kab\.?)\s+(.+)$""")
    private val labeledPrefix = Regex("""(?i)^(kelurahan|kel\.?|desa|kecamatan|kec\.?)\s+""")
    private val postal = Regex("""\s+\d{5}$""")

    fun resolve(
        line: String,
        subLocality: String,
        locality: String,
        subAdmin: String,
        adminArea: String = "",
    ): Names {
        val parts = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var kelurahan = ""
        var kecamatan = ""
        var kabupaten = ""
        var kecamatanAt = -1
        parts.forEachIndexed { index, part ->
            kelurahanLine.matchEntire(part)?.let { kelurahan = clip(it.groupValues[1]) }
            kecamatanLine.matchEntire(part)?.let {
                kecamatan = clip(it.groupValues[1])
                kecamatanAt = index
            }
            kabupatenLine.matchEntire(part)?.let { kabupaten = clip(stripCity(it.groupValues[1])) }
        }
        if (kelurahan.isEmpty() && kecamatanAt > 0) {
            val previous = parts[kecamatanAt - 1]
            if (!isStreet(previous) && !isCity(previous) && !isProvince(previous)) kelurahan = clip(previous)
        }
        val village = clip(subLocality)
        val local = clip(locality)
        val admin = clip(subAdmin)
        if (kecamatan.isEmpty()) {
            kecamatan = when {
                local.isNotEmpty() && !isCity(local) && !isProvince(local) -> local
                admin.isNotEmpty() && !isCity(admin) && !isProvince(admin) -> admin
                village.isNotEmpty() && !isCity(village) && !isProvince(village) && (local.isEmpty() || isCity(local)) -> village
                else -> ""
            }
        }
        if (kelurahan.isEmpty() && village.isNotEmpty() && !village.equals(kecamatan, ignoreCase = true) && !isCity(village)) {
            kelurahan = village
        }
        if (kabupaten.isEmpty()) {
            kabupaten = when {
                isRegency(admin) -> clip(stripCity(admin))
                isRegency(local) -> clip(stripCity(local))
                else -> ""
            }
        }
        var provinsi = provinceName(adminArea)
        if (provinsi.isEmpty()) {
            provinsi = parts.firstNotNullOfOrNull { part ->
                if (!isProvince(part)) null else provinceName(part).ifEmpty { null }
            }.orEmpty()
        }
        return Names(kelurahan.take(80), kecamatan.take(80), kabupaten.take(80), provinsi.take(80))
    }

    private fun clip(raw: String): String =
        raw.trim().replace(labeledPrefix, "").replace(postal, "").trim().take(80)

    private fun stripCity(raw: String): String {
        val text = raw.trim().replace(Regex("""(?i)^(kabupaten|kab\.?|provinsi)\s+"""), "").trim()
        val jakarta = Regex("""(?i)^kota(?:\s+administrasi)?\s+(jakarta.*)$""").matchEntire(text)
        return if (jakarta == null) text else jakarta.groupValues[1].trim()
    }

    private fun provinceName(raw: String): String {
        val text = clip(stripCity(raw))
        val key = text.lowercase(Locale.US)
        if (key.isEmpty()) return ""
        if (key.contains("daerah khusus") || key == "dki jakarta" || key == "jakarta") return "DKI Jakarta"
        return text
    }

    private fun isProvince(value: String): Boolean {
        val text = value.trim().lowercase(Locale.US)
        return text.startsWith("provinsi ") || text.startsWith("dki ") || text.contains("daerah khusus")
    }

    private fun isRegency(value: String): Boolean = isCity(value) && !isProvince(value)

    private fun isCity(value: String): Boolean {
        val text = value.trim().lowercase(Locale.US)
        return text.startsWith("kota ") ||
            text.startsWith("kabupaten ") ||
            text.startsWith("kab. ") ||
            text.startsWith("jakarta") ||
            text.startsWith("dki ") ||
            text.startsWith("daerah khusus")
    }

    private fun isStreet(value: String): Boolean {
        val text = value.trim().lowercase(Locale.US)
        return text.startsWith("jl") ||
            text.startsWith("jalan") ||
            text.startsWith("gg") ||
            text.startsWith("gang") ||
            text.startsWith("rt") ||
            text.startsWith("rw")
    }
}

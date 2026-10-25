package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceNamesTest {
    @Test
    fun splitsAFormattedJakartaAddress() {
        val names = PlaceNames.resolve(
            "Jl. Menteng Raya No.29, RT.1/RW.5, Gondangdia, Kec. Menteng, Kota Jakarta Pusat, DKI Jakarta 10310",
            subLocality = "Gondangdia",
            locality = "Jakarta Pusat",
            subAdmin = "Kota Jakarta Pusat",
            adminArea = "Daerah Khusus Ibukota Jakarta",
        )
        assertEquals("Gondangdia", names.kelurahan)
        assertEquals("Menteng", names.kecamatan)
        assertEquals("Jakarta Pusat", names.kabupaten)
        assertEquals("DKI Jakarta", names.provinsi)
    }

    @Test
    fun usesStructuredNamesWhenTheLineHasNoPrefix() {
        val names = PlaceNames.resolve(
            "",
            subLocality = "Gondangdia",
            locality = "Menteng",
            subAdmin = "Kota Jakarta Pusat",
            adminArea = "DKI Jakarta",
        )
        assertEquals("Gondangdia", names.kelurahan)
        assertEquals("Menteng", names.kecamatan)
        assertEquals("Jakarta Pusat", names.kabupaten)
        assertEquals("DKI Jakarta", names.provinsi)
    }

    @Test
    fun keepsOnlyTheDistrictWhenTheOtherNameIsTheCity() {
        val names = PlaceNames.resolve(
            "Menteng, Jakarta",
            subLocality = "Menteng",
            locality = "Jakarta",
            subAdmin = "",
        )
        assertEquals("", names.kelurahan)
        assertEquals("Menteng", names.kecamatan)
        assertEquals("Jakarta", names.kabupaten)
        assertEquals("", names.provinsi)
    }

    @Test
    fun keepsTheSameNameAtBothLevels() {
        val names = PlaceNames.resolve(
            "Kelurahan Menteng, Kecamatan Menteng, Jakarta Pusat",
            subLocality = "",
            locality = "",
            subAdmin = "",
            adminArea = "Jawa Barat",
        )
        assertEquals("Menteng", names.kelurahan)
        assertEquals("Menteng", names.kecamatan)
        assertEquals("", names.kabupaten)
        assertEquals("Jawa Barat", names.provinsi)
    }

    @Test
    fun keepsKotaOnACityAndDropsItForJakarta() {
        val city = PlaceNames.resolve(
            "",
            subLocality = "Dago",
            locality = "Coblong",
            subAdmin = "Kota Bandung",
            adminArea = "Jawa Barat",
        )
        assertEquals("Dago", city.kelurahan)
        assertEquals("Coblong", city.kecamatan)
        assertEquals("Kota Bandung", city.kabupaten)
        assertEquals("Jawa Barat", city.provinsi)
        val regency = PlaceNames.resolve(
            "",
            subLocality = "",
            locality = "Soreang",
            subAdmin = "Kabupaten Bandung",
            adminArea = "Jawa Barat",
        )
        assertEquals("Soreang", regency.kecamatan)
        assertEquals("Bandung", regency.kabupaten)
    }
}

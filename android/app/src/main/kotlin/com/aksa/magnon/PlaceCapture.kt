package com.aksa.magnon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One fresh GPS fix for the round that just finished. Coordinates stay on the phone. */
object PlaceCapture {
    data class Place(
        val kelurahan: String = "",
        val kecamatan: String = "",
        val kabupaten: String = "",
        val provinsi: String = "",
        val latitude: Double? = null,
        val longitude: Double? = null,
    )
    enum class Reason { OFF, TIMEOUT, COARSE, UNNAMED }

    data class Reading(
        val place: Place = Place(),
        val reason: Reason? = null,
    )

    private const val TAG = "PlaceCapture"
    private const val MAX_ACCURACY_M = 150f
    private const val WAIT_MS = 30_000L
    private const val LAST_FIX_MS = 2 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun district(context: Context): Reading = withContext(Dispatchers.IO) {
        when (val gps = freshGps(context.applicationContext)) {
            is GpsHit -> {
                val named = names(context.applicationContext, gps.location.latitude, gps.location.longitude)
                val place = named.copy(latitude = gps.location.latitude, longitude = gps.location.longitude)
                if (PlaceFill.named(place.kelurahan, place.kecamatan, place.kabupaten, place.provinsi)) {
                    AppLog.i(TAG, "place named")
                    Reading(place)
                } else {
                    AppLog.i(TAG, "place unnamed")
                    Reading(place, Reason.UNNAMED)
                }
            }
            GpsMiss.OFF -> Reading(reason = Reason.OFF).also { AppLog.i(TAG, "place gps_off") }
            GpsMiss.TIMEOUT -> Reading(reason = Reason.TIMEOUT).also { AppLog.i(TAG, "place gps_timeout") }
            GpsMiss.COARSE -> Reading(reason = Reason.COARSE).also { AppLog.i(TAG, "place coarse") }
        }
    }

    fun names(context: Context, latitude: Double, longitude: Double): Place = name(context, latitude, longitude)

    private sealed interface GpsRead

    private sealed class GpsMiss : GpsRead {
        data object OFF : GpsMiss()
        data object TIMEOUT : GpsMiss()
        data object COARSE : GpsMiss()
    }

    private class GpsHit(val location: Location) : GpsRead

    @SuppressLint("MissingPermission")
    private suspend fun freshGps(context: Context): GpsRead {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return GpsMiss.OFF
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return GpsMiss.OFF
        val gpsOn = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        if (!gpsOn) return GpsMiss.OFF
        val cached = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        freshEnough(cached)?.let { hit ->
            AppLog.i(TAG, "place last_gps age_min=${fixAgeMs(hit)?.div(60_000L) ?: -1}")
            return GpsHit(hit)
        }
        if (cached == null) {
            AppLog.i(TAG, "place last_absent")
        } else {
            AppLog.i(TAG, "place last_skip acc=${cached.accuracy.toInt()} age_min=${fixAgeMs(cached)?.div(60_000L) ?: -1}")
        }
        val reading = withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val started = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val request = LocationRequest.Builder(0L)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setDurationMillis(WAIT_MS)
                            .setMaxUpdates(1)
                            .build()
                        manager.getCurrentLocation(LocationManager.GPS_PROVIDER, request, signal, executor) { raw ->
                            if (!cont.isActive) return@getCurrentLocation
                            cont.resume(raw)
                        }
                    } else {
                        manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, executor) { raw ->
                            if (!cont.isActive) return@getCurrentLocation
                            cont.resume(raw)
                        }
                    }
                }
                if (started.isFailure && cont.isActive) cont.resume(null)
            }
        }
        if (reading == null) return GpsMiss.TIMEOUT
        if (freshEnough(reading, ignoreAge = true) == null) return GpsMiss.COARSE
        return GpsHit(reading)
    }

    private fun freshEnough(raw: Location?, ignoreAge: Boolean = false): Location? {
        if (raw == null || !raw.hasAccuracy() || raw.accuracy > MAX_ACCURACY_M) return null
        if (ignoreAge) return raw
        val age = fixAgeMs(raw) ?: return raw
        if (age > LAST_FIX_MS) return null
        return raw
    }

    private fun fixAgeMs(raw: Location): Long? {
        val samples = ArrayList<Long>(2)
        val elapsed = raw.elapsedRealtimeNanos
        if (elapsed > 0L) {
            val age = (SystemClock.elapsedRealtimeNanos() - elapsed) / 1_000_000L
            if (age >= 0L) samples.add(age)
        }
        if (raw.time > 0L) {
            val age = System.currentTimeMillis() - raw.time
            if (age >= 0L) samples.add(age)
        }
        return samples.minOrNull()
    }

    @Suppress("DEPRECATION")
    private fun name(context: Context, latitude: Double, longitude: Double): Place {
        if (!Geocoder.isPresent()) return Place()
        val address = try {
            Geocoder(context, Locale("id", "ID")).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return Place()
        val lines = if (address.maxAddressLineIndex < 0) {
            ""
        } else {
            (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }.joinToString(", ")
        }
        val names = PlaceNames.resolve(
            lines,
            address.subLocality.orEmpty(),
            address.locality.orEmpty(),
            address.subAdminArea.orEmpty(),
            address.adminArea.orEmpty(),
        )
        return Place(names.kelurahan, names.kecamatan, names.kabupaten, names.provinsi)
    }
}

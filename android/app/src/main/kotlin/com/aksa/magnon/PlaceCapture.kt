package com.aksa.magnon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
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
    private const val MAX_ACCURACY_M = 150f
    private const val WAIT_MS = 12_000L
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun district(context: Context): Place = withContext(Dispatchers.IO) {
        val fix = freshGps(context.applicationContext) ?: return@withContext Place()
        val named = name(context.applicationContext, fix.latitude, fix.longitude)
        named.copy(latitude = fix.latitude, longitude = fix.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshGps(context: Context): Location? {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val gpsOn = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        if (!gpsOn) return null
        return withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val started = runCatching {
                    manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, executor) { raw ->
                        if (!cont.isActive) return@getCurrentLocation
                        cont.resume(acceptable(raw))
                    }
                }
                if (started.isFailure && cont.isActive) cont.resume(null)
            }
        }
    }

    /** A coarse reading is not accepted in place of a tight GPS fix. */
    private fun acceptable(location: Location?): Location? {
        if (location == null || !location.hasAccuracy()) return null
        if (location.accuracy > MAX_ACCURACY_M) return null
        return location
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

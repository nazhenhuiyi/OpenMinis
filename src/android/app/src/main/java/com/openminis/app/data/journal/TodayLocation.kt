package com.openminis.app.data.journal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * [T-today-tab] Best-effort location for a note (docs/design/today/SPEC.md §6.1).
 * Same layering as `android-location current` (LocationOffloadHandler): any
 * provider's last fix if recent enough, else one fresh fix with a timeout.
 * Never throws and never prompts — a missing location is simply omitted.
 */
object TodayLocation {
    private const val TAG = "TodayLocation"
    const val MAX_AGE_MS = 10 * 60 * 1000L
    const val TIMEOUT_MS = 15_000L

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Int)

    fun hasPermission(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    suspend fun fix(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val recent = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time <= MAX_AGE_MS }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent.toFix()

        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in providers } ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) { fresh(lm, provider) }?.toFix()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, { it.run() }) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }
                        override fun onProviderDisabled(p: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onProviderEnabled(p: String) {}
                        @Deprecated("API < 29 callback")
                        override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                AppLogger.info(TAG, "fresh fix failed: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun Location.toFix() = Fix(latitude, longitude, accuracy.toInt())

    // ── Place names (§6.1 step 4, §6.2 cache) ─────────────────────────────

    private val placeCache = ConcurrentHashMap<String, String>()

    /** Reverse-geocoded short name, or null (no Geocoder backend on many ROMs — fine). */
    suspend fun placeName(context: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val key = "%.3f,%.3f".format(Locale.US, lat, lng)
        placeCache[key]?.let { return@withContext it }
        if (!Geocoder.isPresent()) return@withContext null
        val name = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                a.thoroughfare ?: a.subLocality ?: a.locality ?: a.featureName ?: a.adminArea
            }
        }.onFailure { AppLogger.info(TAG, "geocode failed: ${it.message}") }.getOrNull()
        name?.also { placeCache[key] = it }
    }

    /** Coordinates as stored in the journal: 5 decimals (~1 m), WGS-84 (§4.4, §6.3). */
    fun attrsFor(fix: Fix): Map<String, String?> = mapOf(
        JournalEntry.KEY_LAT to "%.5f".format(Locale.US, fix.lat),
        JournalEntry.KEY_LNG to "%.5f".format(Locale.US, fix.lng),
        JournalEntry.KEY_ACC to fix.accuracyM.toString(),
    )
}

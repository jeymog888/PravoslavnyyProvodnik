package ru.liturgyguide.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat

/** A foreground request, stopped by success, deadline or the screen's lifecycle. */
class ChurchLocator(context: Context) : ChurchLocationSource {
    private val request = ForegroundLocationRequest(AndroidLocationGateway(context.applicationContext))
    override fun hasPermission(): Boolean = request.hasPermission()
    override fun locateResult(maxAccuracyMeters: Float, maxLocationAgeMillis: Long,
        onResult: (ForegroundLocationResult) -> Unit) = request.locateResult(maxAccuracyMeters, maxLocationAgeMillis, onResult)

    // Preserve callers that pin a church or search only the local directory.
    fun locate(maxAccuracyMeters: Float = 10_000f, maxLocationAgeMillis: Long = 120_000L,
        onResult: (Location?, String?) -> Unit) = locateResult(maxAccuracyMeters, maxLocationAgeMillis) { result ->
        when (result) {
            is ForegroundLocationResult.Failed -> onResult(null, result.reason.message)
            is ForegroundLocationResult.Located -> onResult(Location("feoria").apply {
                latitude = result.fix.latitude; longitude = result.fix.longitude
                result.fix.accuracyMeters?.let { accuracy = it }
                elapsedRealtimeNanos = result.fix.elapsedRealtimeNanos
            }, null)
        }
    }
    override fun stop() = request.stop()
}

internal class AndroidLocationGateway(private val context: Context) : ForegroundLocationGateway {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)
    override fun providers(): List<String> = manager.allProviders
    override fun isProviderEnabled(provider: String): Boolean = manager.isProviderEnabled(provider)
    override fun lastKnown(provider: String): ForegroundLocationFix? = manager.getLastKnownLocation(provider)?.asFix()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun after(delayMillis: Long, action: () -> Unit): LocationCancellation {
        val pending = Runnable(action)
        check(handler.postDelayed(pending, delayMillis))
        return LocationCancellation { handler.removeCallbacks(pending) }
    }
    override fun subscribe(provider: String, onFix: (ForegroundLocationFix) -> Unit): LocationCancellation {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.asFix())
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        // A rejected first fix must not consume a one-shot request.
        try { manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper()) }
        catch (failure: Exception) { runCatching { manager.removeUpdates(listener) }; throw failure }
        return LocationCancellation { manager.removeUpdates(listener) }
    }
    private fun Location.asFix() = ForegroundLocationFix(latitude, longitude, accuracy.takeIf { hasAccuracy() }, elapsedRealtimeNanos)
}

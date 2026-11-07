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

/** A single foreground location request. No location is stored or sent by the app. */
class ChurchLocator(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var active: LocationListener? = null
    private var deadline: Runnable? = null

    @Suppress("DEPRECATION")
    fun locate(onResult: (Location?, String?) -> Unit) {
        stop()
        val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { onResult(null, "Разрешите доступ к местоположению или выберите храм вручную"); return }
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { (it != LocationManager.GPS_PROVIDER || fine) && manager.isProviderEnabled(it) }
        if (providers.isEmpty()) { onResult(null, "На телефоне выключено определение местоположения. Можно выбрать храм вручную"); return }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (active !== this) return
                if (!location.hasAccuracy() || location.accuracy > 500f) return
                stop()
                onResult(location, null)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..120_000_000_000L }
                .minByOrNull { it.accuracy }
            if (cached != null && cached.hasAccuracy() && cached.accuracy <= 500f) { onResult(cached, null); return }
            active = listener
            providers.forEach { manager.requestSingleUpdate(it, listener, Looper.getMainLooper()) }
            deadline = Runnable {
                if (active === listener) { stop(); onResult(null, "Не удалось определить местоположение. Выберите храм вручную") }
            }.also { handler.postDelayed(it, 20_000L) }
        } catch (_: SecurityException) { stop(); onResult(null, "Доступ к местоположению недоступен. Выберите храм вручную") }
        catch (_: IllegalArgumentException) { stop(); onResult(null, "Определение местоположения недоступно. Выберите храм вручную") }
    }

    fun stop() {
        deadline?.let(handler::removeCallbacks)
        deadline = null
        active?.let { listener -> runCatching { manager.removeUpdates(listener) } }
        active = null
    }
}

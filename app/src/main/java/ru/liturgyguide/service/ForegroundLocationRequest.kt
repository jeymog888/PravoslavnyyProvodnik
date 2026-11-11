package ru.liturgyguide.service

data class ForegroundLocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?,
    val elapsedRealtimeNanos: Long)

enum class LocationFailure(val message: String) {
    PERMISSION("Можно продолжить без геолокации: найдите храм по названию или разрешите доступ к местоположению"),
    DISABLED("На телефоне выключено определение местоположения"),
    UNAVAILABLE("Не удалось обратиться к местоположению телефона. Попробуйте снова или найдите храм по названию"),
    TIMEOUT("Не удалось определить место. Попробуйте снова или найдите храм по названию"),
    INACCURATE("Место определено слишком приблизительно. Попробуйте снова или найдите храм по названию")
}

sealed interface ForegroundLocationResult {
    data class Located(val fix: ForegroundLocationFix) : ForegroundLocationResult
    data class Failed(val reason: LocationFailure) : ForegroundLocationResult
}

fun interface LocationCancellation { fun cancel() }

/** All callbacks and operations belong to the foreground thread. No location is stored here. */
interface ForegroundLocationGateway {
    fun hasPermission(): Boolean
    fun isLocationEnabled(): Boolean?
    fun providers(): List<String>
    fun isProviderEnabled(provider: String): Boolean
    fun lastKnown(provider: String): ForegroundLocationFix?
    fun subscribe(provider: String, onFix: (ForegroundLocationFix) -> Unit): LocationCancellation
    fun elapsedRealtimeNanos(): Long
    fun after(delayMillis: Long, action: () -> Unit): LocationCancellation
}

interface ChurchLocationSource {
    fun hasPermission(): Boolean
    fun locateResult(maxAccuracyMeters: Float = 10_000f, maxLocationAgeMillis: Long = 120_000L,
        onResult: (ForegroundLocationResult) -> Unit)
    fun stop()
}

/** One bounded request may receive several fixes before an acceptable one arrives. */
class ForegroundLocationRequest(private val gateway: ForegroundLocationGateway) : ChurchLocationSource {
    private class Request(val onResult: (ForegroundLocationResult) -> Unit) {
        val cancellations = mutableListOf<LocationCancellation>()
        var imprecise = false
    }
    private var active: Request? = null

    override fun hasPermission(): Boolean = runCatching { gateway.hasPermission() }.getOrDefault(false)

    override fun locateResult(maxAccuracyMeters: Float, maxLocationAgeMillis: Long,
        onResult: (ForegroundLocationResult) -> Unit) {
        stop()
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters in 1f..10_000f)
        require(maxLocationAgeMillis in 0L..120_000L)
        val request = Request(onResult)
        active = request
        fun fail(reason: LocationFailure) = finish(request, ForegroundLocationResult.Failed(reason))
        if (!hasPermission()) { fail(LocationFailure.PERMISSION); return }
        if (runCatching { gateway.isLocationEnabled() }.getOrNull() == false) {
            fail(LocationFailure.DISABLED); return
        }
        val available = runCatching { gateway.providers() }.getOrDefault(emptyList())
        val providers = (listOf("fused", "network", "gps") + available).distinct()
            .filter { it != "passive" && it in available }
            .filter { runCatching { gateway.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { fail(LocationFailure.UNAVAILABLE); return }

        fun acceptable(fix: ForegroundLocationFix): Boolean {
            val accuracy = fix.accuracyMeters ?: return false
            // Old or invalid coordinates do not prove that the phone located us imprecisely.
            // Relax only accuracy here; keep the same coordinate and age checks for the diagnosis.
            if (accuracy.isFinite() && accuracy > maxAccuracyMeters && LocationFixPolicy.accepts(
                    fix.latitude, fix.longitude, maxAccuracyMeters, fix.elapsedRealtimeNanos,
                    gateway.elapsedRealtimeNanos(), maxAccuracyMeters, maxLocationAgeMillis)) request.imprecise = true
            return LocationFixPolicy.accepts(fix.latitude, fix.longitude, accuracy, fix.elapsedRealtimeNanos,
                gateway.elapsedRealtimeNanos(), maxAccuracyMeters, maxLocationAgeMillis)
        }
        val cached = providers.mapNotNull { runCatching { gateway.lastKnown(it) }.getOrNull() }
            .filter(::acceptable).minByOrNull { it.accuracyMeters ?: Float.MAX_VALUE }
        if (cached != null) { finish(request, ForegroundLocationResult.Located(cached)); return }
        val deadline = runCatching { gateway.after(TIMEOUT_MILLIS) {
            fail(if (request.imprecise) LocationFailure.INACCURATE else LocationFailure.TIMEOUT)
        } }.getOrNull()
        if (deadline == null) { fail(LocationFailure.UNAVAILABLE); return }
        keep(request, deadline)
        var subscribed = false
        for (provider in providers) {
            if (active !== request) break
            val subscription = runCatching { gateway.subscribe(provider) { fix ->
                if (active === request && acceptable(fix)) finish(request, ForegroundLocationResult.Located(fix))
            } }.getOrNull() ?: continue
            subscribed = true
            keep(request, subscription)
        }
        if (!subscribed && active === request) {
            fail(if (hasPermission()) LocationFailure.UNAVAILABLE else LocationFailure.PERMISSION)
        }
    }

    private fun keep(request: Request, cancellation: LocationCancellation) {
        if (active === request) request.cancellations += cancellation else runCatching { cancellation.cancel() }
    }

    private fun finish(request: Request, result: ForegroundLocationResult) {
        if (active !== request) return
        stop()
        request.onResult(result)
    }

    override fun stop() {
        val previous = active
        active = null
        previous?.cancellations?.forEach { runCatching { it.cancel() } }
        previous?.cancellations?.clear()
    }

    companion object { const val TIMEOUT_MILLIS = 20_000L }
}

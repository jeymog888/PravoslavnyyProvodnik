package ru.liturgyguide.service

/** Accept recent fixes using elapsed realtime, independent of the phone's calendar clock. */
object LocationFixPolicy {
    fun accepts(latitude: Double, longitude: Double, accuracyMeters: Float, fixElapsedNanos: Long,
        nowElapsedNanos: Long, maxAccuracyMeters: Float = 10_000f, maxAgeMillis: Long = 120_000L): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracyMeters.isFinite() || accuracyMeters <= 0f || accuracyMeters > maxAccuracyMeters ||
            !maxAccuracyMeters.isFinite() || maxAccuracyMeters !in 1f..10_000f || maxAgeMillis !in 0L..120_000L ||
            fixElapsedNanos < 0 || nowElapsedNanos < fixElapsedNanos) return false
        return nowElapsedNanos - fixElapsedNanos <= maxAgeMillis * 1_000_000L
    }
}
